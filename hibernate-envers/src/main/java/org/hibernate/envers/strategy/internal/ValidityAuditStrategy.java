/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.envers.strategy.internal;

import static org.hibernate.envers.internal.entities.mapper.relation.query.QueryConstants.MIDDLE_ENTITY_ALIAS;
import static org.hibernate.envers.internal.entities.mapper.relation.query.QueryConstants.REVISION_PARAMETER;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.hibernate.LockOptions;
import org.hibernate.Session;
import org.hibernate.engine.jdbc.spi.JdbcCoordinator;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.boot.model.BasicAttribute;
import org.hibernate.envers.boot.model.Column;
import org.hibernate.envers.boot.model.ManyToOneAttribute;
import org.hibernate.envers.configuration.Configuration;
import org.hibernate.envers.configuration.internal.metadata.RevisionInfoHelper;
import org.hibernate.envers.exception.AuditException;
import org.hibernate.envers.internal.entities.mapper.PersistentCollectionChangeData;
import org.hibernate.envers.internal.entities.mapper.relation.MiddleComponentData;
import org.hibernate.envers.internal.entities.mapper.relation.MiddleIdData;
import org.hibernate.envers.internal.revisioninfo.RevisionInfoNumberReader;
import org.hibernate.envers.internal.synchronization.SessionCacheCleaner;
import org.hibernate.envers.internal.tools.query.Parameters;
import org.hibernate.envers.internal.tools.query.QueryBuilder;
import org.hibernate.envers.strategy.AuditStrategy;
import org.hibernate.envers.strategy.spi.AuditStrategyContext;
import org.hibernate.envers.strategy.spi.MappingContext;
import org.hibernate.event.spi.EventSource;
import org.hibernate.persister.entity.JoinedSubclassEntityPersister;
import org.hibernate.persister.entity.Queryable;
import org.hibernate.persister.entity.UnionSubclassEntityPersister;
import org.hibernate.property.access.spi.Getter;
import org.hibernate.sql.Update;
import org.hibernate.type.BasicType;
import org.hibernate.type.CollectionType;
import org.hibernate.type.ComponentType;
import org.hibernate.type.MapType;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.Type;

/**
 * An audit strategy implementation that persists and fetches audit information using a validity
 * algorithm, based on the start-revision and end-revision of a row in the audit table schema.
 * <p>
 * This algorithm works as follows:
 * <ul>
 * <li>For a new row, only the start-revision column is set in the row.</li>
 * <li>Concurrently, the end-revision of the prior audit row is set to the current revision</li>
 * <li>Queries using a between start and end revision predicate rather than using subqueries.</li>
 * </ul>
 * <p>
 * This has a few important consequences which must be considered:
 * <ul>
 * <li>Persisting audit information is sightly slower due to an extra update required</li>
 * <li>Retreiving audit information is considerably faster</li>
 * </ul>
 *
 * @author Stephanie Pau
 * @author Adam Warski (adam at warski dot org)
 * @author Lukasz Antoniak (lukasz dot antoniak at gmail dot com)
 * @author Chris Cranford
 */
public class ValidityAuditStrategy implements AuditStrategy {
	/**
	 * getter for the revision entity field annotated with @RevisionTimestamp
	 */
	private Getter revisionTimestampGetter;

	private final SessionCacheCleaner sessionCacheCleaner;

	public ValidityAuditStrategy() {
		sessionCacheCleaner = new SessionCacheCleaner();
	}

	@Override
	public void postInitialize(AuditStrategyContext context) {
		setRevisionTimestampGetter( context.getRevisionInfoTimestampAccessor() );
	}

	@Override
	public void addAdditionalColumns(MappingContext mappingContext) {
		if ( !mappingContext.isRevisionEndTimestampOnly() ) {
			// Add revision end field since mapping is not requesting only the timestamp.
			final ManyToOneAttribute revEndMapping = new ManyToOneAttribute(
					mappingContext.getConfiguration().getRevisionEndFieldName(),
					mappingContext.getRevisionInfoPropertyType(),
					true,
					true,
					false,
					mappingContext.getRevisionInfoExplicitTypeName()
			);

			RevisionInfoHelper.addOrModifyColumn(
					revEndMapping,
					mappingContext.getConfiguration().getRevisionEndFieldName()
			);

			mappingContext.getEntityMapping().addAttribute( revEndMapping );
		}

		if ( mappingContext.getConfiguration().isRevisionEndTimestampEnabled() ) {
			// add a column for the timestamp of the end revision
			final String revisionInfoTimestampTypeName;
			if ( mappingContext.getConfiguration().isRevisionEndTimestampNumeric() ) {
				revisionInfoTimestampTypeName = StandardBasicTypes.LONG.getName();
			}
			else {
				revisionInfoTimestampTypeName = StandardBasicTypes.TIMESTAMP.getName();
			}

			String revEndTimestampPropertyName = mappingContext.getConfiguration().getRevisionEndTimestampFieldName();
			String revEndTimestampColumnName = revEndTimestampPropertyName;
			if ( !mappingContext.getConfiguration().isRevisionEndTimestampUseLegacyPlacement() ) {
				if ( mappingContext.isRevisionEndTimestampOnly() ) {
					// properties across a joined inheritance model cannot have the same name.
					// what is done here is we adjust just the property name so it is seen as unique in
					// the mapping model but keep the column representation with the configured timestamp column name.
					revEndTimestampPropertyName = mappingContext.getConfiguration().getRevisionEndTimestampFieldName()
							+ "_"
							+ mappingContext.getEntityMapping().getAuditTableData().getAuditTableName();
				}
			}
			final BasicAttribute revEndTimestampMapping = new BasicAttribute(
					revEndTimestampPropertyName,
					revisionInfoTimestampTypeName,
					true,
					true,
					false
			);
			revEndTimestampMapping.addColumn( new Column( revEndTimestampColumnName ) );
			mappingContext.getEntityMapping().addAttribute( revEndTimestampMapping );
		}
	}

	@Override
	public void perform(
			final Session session,
			final String entityName,
			final Configuration configuration,
			final Object id,
			final Object data,
			final Object revision) {
		final String auditedEntityName = configuration.getAuditEntityName( entityName );

		// Save the audit data
		session.save( auditedEntityName, data );

		// Update the end date of the previous row.
		//
		// When application reuses identifiers of previously removed entities:
		// The UPDATE statement will no-op if an entity with a given identifier has been
		// inserted for the first time. But in case a deleted primary key value was
		// reused, this guarantees correct strategy behavior: exactly one row with
		// null end date exists for each identifier.
		final boolean reuseEntityIdentifier = configuration.isAllowIdentifierReuse();
		if ( reuseEntityIdentifier || getRevisionType( configuration, data ) != RevisionType.ADD ) {
			// Register transaction completion process to guarantee execution of UPDATE statement after INSERT.
			( (EventSource) session ).getActionQueue().registerProcess( sessionImplementor -> {
				// Construct the update contexts
				final List<UpdateContext> contexts = getUpdateContexts(
						entityName,
						auditedEntityName,
						sessionImplementor,
						configuration,
						id,
						revision
				);

				if ( contexts.isEmpty() ) {
					throw new AuditException(
							String.format(
									Locale.ENGLISH,
									"Failed to build update contexts for entity %s and id %s",
									auditedEntityName,
									id
							)
					);
				}

				for ( UpdateContext context : contexts ) {
					final int rows = executeUpdate( sessionImplementor, context );
					if ( rows != 1 ) {
						final RevisionType revisionType = getRevisionType( configuration, data );
						if ( !reuseEntityIdentifier || revisionType != RevisionType.ADD ) {
							throw new AuditException(
									String.format(
											Locale.ENGLISH,
											"Cannot update previous revision for entity %s and id %s (%s rows modified).",
											auditedEntityName,
											id,
											rows
									)
							);
						}
					}
				}
			} );
		}
		sessionCacheCleaner.scheduleAuditDataRemoval( session, data );
	}

	@Override
	@SuppressWarnings({"unchecked"})
	public void performCollectionChange(
			Session session,
			String entityName,
			String propertyName,
			Configuration configuration,
			PersistentCollectionChangeData persistentCollectionChangeData, Object revision) {
		final QueryBuilder qb = new QueryBuilder(
				persistentCollectionChangeData.getEntityName(),
				MIDDLE_ENTITY_ALIAS,
				( (SharedSessionContractImplementor) session ).getFactory()
		);

		final String originalIdPropName = configuration.getOriginalIdPropertyName();
		final Map<String, Object> originalId = (Map<String, Object>) persistentCollectionChangeData.getData().get(
				originalIdPropName
		);
		final String revisionFieldName = configuration.getRevisionFieldName();
		final String revisionTypePropName = configuration.getRevisionTypePropertyName();
		final String ordinalPropName = configuration.getEmbeddableSetOrdinalPropertyName();

		// Adding a parameter for each id component, except the rev number and type.
		for ( Map.Entry<String, Object> originalIdEntry : originalId.entrySet() ) {
			if ( !revisionFieldName.equals( originalIdEntry.getKey() )
					&& !revisionTypePropName.equals( originalIdEntry.getKey() )
					&& !ordinalPropName.equals( originalIdEntry.getKey() ) ) {
				qb.getRootParameters().addWhereWithParam(
						originalIdPropName + "." + originalIdEntry.getKey(),
						true, "=", originalIdEntry.getValue()
				);
			}
		}

		if ( isNonIdentifierWhereConditionsRequired( entityName, propertyName, (SessionImplementor) session ) ) {
			addNonIdentifierWhereConditions( qb, persistentCollectionChangeData.getData(), originalIdPropName );
		}

		addEndRevisionNullRestriction( configuration, qb.getRootParameters() );

		final List<Object> l = qb.toQuery( session ).setLockOptions( LockOptions.UPGRADE ).list();

		// Update the last revision if one exists.
		// HHH-5967: with collections, the same element can be added and removed multiple times. So even if it's an
		// ADD, we may need to update the last revision.
		if ( l.size() > 0 ) {
			updateLastRevision(
					session, configuration, l, originalId, persistentCollectionChangeData.getEntityName(), revision
			);
		}

		// Save the audit data
		session.save( persistentCollectionChangeData.getEntityName(), persistentCollectionChangeData.getData() );
		sessionCacheCleaner.scheduleAuditDataRemoval( session, persistentCollectionChangeData.getData() );
	}

	/**
	 * {@inheritDoc}
	 *
	 * For this implmenetation, the revision-end column is used
	 * <p>
	 * {@code e.revision <= :revision and (e.endRevision > :revision or e.endRevision is null}
	 */
	@Override
	public void addEntityAtRevisionRestriction(
			Configuration configuration,
			QueryBuilder rootQueryBuilder,
			Parameters parameters,
			String revisionProperty,
			String revisionEndProperty,
			boolean addAlias,
			MiddleIdData idData,
			String revisionPropertyPath,
			String originalIdPropertyName,
			String alias1,
			String alias2,
			boolean inclusive) {
		addRevisionRestriction( parameters, revisionProperty, revisionEndProperty, addAlias, inclusive );
	}

	/**
	 * {@inheritDoc}
	 *
	 * For this implmenetation, the revision-end column is used
	 * <p>
	 * {@code e.revision <= :revision and (e.endRevision > :revision or e.endRevision is null}
	 */
	@Override
	public void addAssociationAtRevisionRestriction(
			QueryBuilder rootQueryBuilder,
			Parameters parameters,
			String revisionProperty,
			String revisionEndProperty,
			boolean addAlias,
			MiddleIdData referencingIdData,
			String versionsMiddleEntityName,
			String eeOriginalIdPropertyPath,
			String revisionPropertyPath,
			String originalIdPropertyName,
			String alias1,
			boolean inclusive,
			MiddleComponentData... componentDatas) {
		addRevisionRestriction( parameters, revisionProperty, revisionEndProperty, addAlias, inclusive );
	}

	/**
	 * @deprecated with no replacement.
	 */
	@Deprecated(since = "5.4")
	public void setRevisionTimestampGetter(Getter revisionTimestampGetter) {
		this.revisionTimestampGetter = revisionTimestampGetter;
	}

	private void addRevisionRestriction(
			Parameters rootParameters, String revisionProperty, String revisionEndProperty,
			boolean addAlias, boolean inclusive) {
		// e.revision <= _revision and (e.endRevision > _revision or e.endRevision is null)
		Parameters subParm = rootParameters.addSubParameters( "or" );
		rootParameters.addWhereWithNamedParam( revisionProperty, addAlias, inclusive ? "<=" : "<", REVISION_PARAMETER );
		subParm.addWhereWithNamedParam(
				revisionEndProperty + ".id", addAlias, inclusive ? ">" : ">=", REVISION_PARAMETER
		);
		subParm.addWhere( revisionEndProperty, addAlias, "is", "null", false );
	}

	@SuppressWarnings({"unchecked"})
	private RevisionType getRevisionType(Configuration configuration, Object data) {
		return (RevisionType) ( (Map<String, Object>) data ).get( configuration.getRevisionTypePropertyName() );
	}

	@SuppressWarnings({"unchecked"})
	private void updateLastRevision(
			Session session,
			Configuration configuration,
			List<Object> l,
			Object id,
			String auditedEntityName,
			Object revision) {
		// There should be one entry
		if ( l.size() == 1 ) {
			// Setting the end revision to be the current rev
			Object previousData = l.get( 0 );
			String revisionEndFieldName = configuration.getRevisionEndFieldName();
			( (Map<String, Object>) previousData ).put( revisionEndFieldName, revision );

			if ( configuration.isRevisionEndTimestampEnabled() ) {
				// Determine the value of the revision property annotated with @RevisionTimestamp
				String revEndTimestampFieldName = configuration.getRevisionEndTimestampFieldName();
				Object revEndTimestampObj = this.revisionTimestampGetter.get( revision );
				Date revisionEndTimestamp = convertRevEndTimestampToDate( revEndTimestampObj );

				// Setting the end revision timestamp
				( (Map<String, Object>) previousData ).put( revEndTimestampFieldName, revisionEndTimestamp );
			}

			// Saving the previous version
			session.save( auditedEntityName, previousData );
			sessionCacheCleaner.scheduleAuditDataRemoval( session, previousData );
		}
		else {
			throw new RuntimeException( "Cannot find previous revision for entity " + auditedEntityName + " and id " + id );
		}
	}

	private Date convertRevEndTimestampToDate(Object revEndTimestampObj) {
		// convert to a java.util.Date
		if ( revEndTimestampObj instanceof Date ) {
			return (Date) revEndTimestampObj;
		}
		return new Date( (Long) revEndTimestampObj );
	}

	private Long convertRevEndTimestampToLong(Object revEndTimstampObj) {
		if ( revEndTimstampObj instanceof Date ) {
			return ( (Date) revEndTimstampObj ).getTime();
		}
		return (Long) revEndTimstampObj;
	}

	private Object getRevEndTimestampValue(Configuration configuration, Object value) {
		if ( configuration.isRevisionEndTimestampNumeric() ) {
			return convertRevEndTimestampToLong( value );
		}
		return convertRevEndTimestampToDate( value );
	}

	private Queryable getQueryable(String entityName, SessionImplementor sessionImplementor) {
		return (Queryable) sessionImplementor.getFactory()
				.getMappingMetamodel()
				.getEntityDescriptor( entityName );
	}

	private void addEndRevisionNullRestriction(Configuration configuration, Parameters rootParameters) {
		rootParameters.addWhere( configuration.getRevisionEndFieldName(), true, "is", "null", false );
	}

	private void addNonIdentifierWhereConditions(QueryBuilder qb, Map<String, Object> data, String originalIdPropertyName) {
		final Parameters parameters = qb.getRootParameters();
		for ( Map.Entry<String, Object> entry : data.entrySet() ) {
			if ( !originalIdPropertyName.equals( entry.getKey() ) ) {
				if ( entry.getValue() != null ) {
					parameters.addWhereWithParam( entry.getKey(), true, "=", entry.getValue() );
				}
				else {
					parameters.addNullRestriction( entry.getKey(), true );
				}
			}
		}
	}

	private boolean isNonIdentifierWhereConditionsRequired(String entityName, String propertyName, SessionImplementor session) {
		final Type propertyType = session.getSessionFactory()
				.getMappingMetamodel()
				.getEntityDescriptor( entityName ).getPropertyType( propertyName );
		if ( propertyType.isCollectionType() ) {
			final CollectionType collectionType = (CollectionType) propertyType;
			final Type collectionElementType = collectionType.getElementType( session.getSessionFactory() );
			if ( collectionElementType instanceof ComponentType ) {
				// required for Embeddables
				return true;
			}
			else if ( isMaterializedClob( collectionElementType ) ) {
				// for Map<> using @Lob annotations
				return collectionType instanceof MapType;
			}
		}
		return false;
	}

	private boolean isMaterializedClob(Type collectionElementType) {
		if ( collectionElementType instanceof BasicType<?> ) {
			final BasicType<?> basicType = (BasicType<?>) collectionElementType;
			return basicType.getJavaType() == String.class && (
					basicType.getJdbcType().getJdbcTypeCode() == Types.CLOB
							|| basicType.getJdbcType().getJdbcTypeCode() == Types.NCLOB
			);
		}
		return false;
	}

	/**
	 * Executes the {@link UpdateContext} within the scope of the specified session.
	 *
	 * @param session the session
	 * @param context the update context to be executed
	 * @return the number of rows affected by the operation
	 */
	private int executeUpdate(SessionImplementor session, UpdateContext context) {
		final String sql = context.toStatementString();
		final JdbcCoordinator jdbcCoordinator = session.getJdbcCoordinator();

		final PreparedStatement statement = jdbcCoordinator.getStatementPreparer().prepareStatement( sql );
		return session.doReturningWork(
				connection -> {
					try {
						int index = 1;
						for ( QueryParameterBinding binding : context.getBindings() ) {
							index += binding.bind( index, statement, session );
						}
						int result = jdbcCoordinator.getResultSetReturn().executeUpdate( statement );
						return result;
					}
					finally {
						jdbcCoordinator.getLogicalConnection().getResourceRegistry().release( statement );
						jdbcCoordinator.afterStatementExecution();
					}
				}
		);
	}

	private List<UpdateContext> getUpdateContexts(
			String entityName,
			String auditEntityName,
			SessionImplementor session,
			Configuration configuration,
			Object id,
			Object revision) {

		Queryable entity = getQueryable( entityName, session );
		final List<UpdateContext> contexts = new ArrayList<>( 0 );

		// HHH-9062 - update inherited
		if ( configuration.isRevisionEndTimestampEnabled() && !configuration.isRevisionEndTimestampUseLegacyPlacement() ) {
			if ( entity instanceof JoinedSubclassEntityPersister ) {
				// iterate subclasses, excluding root
				while ( entity.getMappedSuperclass() != null ) {
					contexts.add(
							getNonRootUpdateContext(
									entityName,
									auditEntityName,
									session,
									configuration,
									id,
									revision
							)
					);
					entityName = entity.getEntityMetamodel().getSuperclass();
					auditEntityName = configuration.getAuditEntityName( entityName );
					entity = getQueryable( entityName, session );
				}
			}
		}

		// add root
		contexts.add(
				getUpdateContext(
						entityName,
						auditEntityName,
						session,
						configuration,
						id,
						revision
				)
		);

		return contexts;
	}

	private UpdateContext getUpdateContext(
			String entityName,
			String auditEntityName,
			SessionImplementor session,
			Configuration configuration,
			Object id,
			Object revision) {

		final Queryable entity = getQueryable( entityName, session );
		final Queryable rootEntity = getQueryable( entity.getRootEntityName(), session );
		final Queryable auditEntity = getQueryable( auditEntityName, session );
		final Queryable rootAuditEntity = getQueryable( auditEntity.getRootEntityName(), session );
		final Queryable revisionEntity = getQueryable( configuration.getRevisionInfo().getRevisionInfoEntityName(), session );

		final Number revisionNumber = getRevisionNumber( configuration, revision );
		final Type revisionNumberType = revisionEntity.getIdentifierType();

		// The expected SQL is an update statement as follows:
		// UPDATE audited_entity SET REVEND = ? [, REVEND_TSTMP = ?] WHERE (entity_id) = ? AND REV <> ? AND REVEND is null
		final UpdateContext context = new UpdateContext( session.getFactory() );
		context.setTableName( getUpdateTableName( rootEntity, rootAuditEntity, auditEntity ) );

		// Apply "SET REVEND = ?"  portion of the SQL
		final String revEndColumnName = configuration.getRevisionEndFieldName();
		context.addColumn( rootAuditEntity.toColumns( revEndColumnName )[ 0 ] );
		context.bind( revisionNumber, revisionNumberType );

		if ( configuration.isRevisionEndTimestampEnabled() ) {
			final String revEndTimestampColumnName = configuration.getRevisionEndTimestampFieldName();
			final Type revEndTimestampType = rootAuditEntity.getPropertyType( revEndTimestampColumnName );
			final Object revisionTimestamp = revisionTimestampGetter.get( revision );
			// Apply optional "[, REVEND_TSTMP = ?]" portion of the SQL
			context.addColumn( rootAuditEntity.toColumns( revEndTimestampColumnName )[ 0 ] );
			context.bind( getRevEndTimestampValue( configuration, revisionTimestamp ), revEndTimestampType );
		}

		// Apply "WHERE (entity_id) = ?"
		context.addPrimaryKeyColumns( rootEntity.getIdentifierColumnNames() );
		context.bind( id, rootEntity.getIdentifierType() );

		// Apply "AND REV <> ?"
		final String path = configuration.getRevisionNumberPath();
		context.addWhereColumn( rootAuditEntity.toColumns( path )[ 0 ], " <> ?" );
		context.bind( revisionNumber, rootAuditEntity.getPropertyType( path ) );

		// Apply "AND REVEND is null"
		context.addWhereColumn( auditEntity.toColumns( revEndColumnName )[ 0 ], " is null" );

		return context;
	}

	/**
	 * Creates the update context used to modify the revision end timestamp values for a non-root entity.
	 * This is only used to set the revision end timestamp for joined inheritance non-root entity mappings.
	 *
	 * @param entityName the entity name
	 * @param auditEntityName the audited entity name
	 * @param session the session
	 * @param configuration the configuration
	 * @param id the entity identifier
	 * @param revision the revision entity
	 * @return the created update context instance, never {@code null}.
	 */
	private UpdateContext getNonRootUpdateContext(
			String entityName,
			String auditEntityName,
			SessionImplementor session,
			Configuration configuration,
			Object id,
			Object revision) {

		final Queryable entity = getQueryable( entityName, session );
		final Queryable auditEntity = getQueryable( auditEntityName, session );

		final String revEndTimestampColumnName = configuration.getRevisionEndTimestampFieldName();
		final Type revEndTimestampType = auditEntity.getPropertyType( revEndTimestampColumnName );

		// The expected SQL is an update statement as follows:
		// UPDATE audited_entity SET REVEND_TSTMP = ? WHERE (entity_id) = ? AND REV <> ? AND REVEND_TSMTP is null
		final UpdateContext context = new UpdateContext( session.getFactory() );
		context.setTableName( getUpdateTableName( entity, auditEntity, auditEntity ) );

		// Apply "SET REVEND_TSTMP = ?" portion of the SQL
		final Object revisionTimestamp = revisionTimestampGetter.get( revision );
		context.addColumn( auditEntity.toColumns( revEndTimestampColumnName )[ 0 ] );
		context.bind( getRevEndTimestampValue( configuration, revisionTimestamp ), revEndTimestampType );

		// Apply "WHERE (entity_id) = ? AND REV <> ?" portion of the SQL
		final Number revisionNumber = getRevisionNumber( configuration, revision );

		// Apply "WHERE (entity_id) = ?"
		context.addPrimaryKeyColumns( entity.getIdentifierColumnNames() );
		context.bind( id, entity.getIdentifierType() );

		// Apply "AND REV <> ?"
		context.addWhereColumn( configuration.getRevisionFieldName(), " <> ?" );
		context.bind( revisionNumber, auditEntity.getPropertyType( configuration.getRevisionNumberPath() ) );

		// Apply "AND REVEND_TSTMP is null"
		context.addWhereColumn( auditEntity.toColumns( revEndTimestampColumnName )[ 0 ], " is null" );

		return context;
	}

	private Number getRevisionNumber(Configuration configuration, Object revisionEntity) {
		final RevisionInfoNumberReader reader = configuration.getRevisionInfo().getRevisionInfoNumberReader();
		return reader.getRevisionNumber( revisionEntity );
	}

	private String getUpdateTableName(Queryable rootEntity, Queryable rootAuditEntity, Queryable auditEntity) {
		if ( UnionSubclassEntityPersister.class.isInstance( rootEntity ) ) {
			// This is the condition causing all the problems of the generated SQL update;
			// the problem being that we currently try to update the inline view made of the union query.
			//
			// This is hacky to get the root table name for the union subclass style entities because
			// it relies on internal behavior of UnionSubclassEntityPersister.
			return auditEntity.getSubclassTableName( 0 );
		}
		return rootAuditEntity.getTableName();
	}

	/**
	 * An {@link Update} that can also track parameter bindings.
	 */
	private static class UpdateContext extends Update {
		private final List<QueryParameterBinding> bindings = new ArrayList<>( 0 );

		public UpdateContext(SessionFactoryImplementor sessionFactory) {
			super ( sessionFactory.getJdbcServices().getDialect() );
		}

		public List<QueryParameterBinding> getBindings() {
			return bindings;
		}

		public void bind(Object value, Type type) {
			bindings.add( new QueryParameterBinding( value, type ) );
		}
	}

	private static class QueryParameterBinding {
		private final Type type;
		private final Object value;

		public QueryParameterBinding(Object value, Type type) {
			this.type = type;
			this.value = value;
		}

		public int bind(int index, PreparedStatement statement, SessionImplementor session) throws SQLException {
			type.nullSafeSet( statement, value, index, session );
			return type.getColumnSpan( session.getSessionFactory() );
		}
	}
}
