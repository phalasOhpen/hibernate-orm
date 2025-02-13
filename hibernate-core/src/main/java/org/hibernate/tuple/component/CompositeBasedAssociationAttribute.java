/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.tuple.component;

import org.hibernate.FetchMode;
import org.hibernate.engine.FetchStyle;
import org.hibernate.engine.FetchTiming;
import org.hibernate.engine.spi.CascadeStyle;
import org.hibernate.engine.spi.LoadQueryInfluencers;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.loader.PropertyPath;
import org.hibernate.persister.collection.CollectionPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.persister.entity.Joinable;
import org.hibernate.persister.spi.HydratedCompoundValueHandler;
import org.hibernate.persister.walking.internal.FetchOptionsHelper;
import org.hibernate.persister.walking.internal.StandardAnyTypeDefinition;
import org.hibernate.persister.walking.spi.AnyMappingDefinition;
import org.hibernate.persister.walking.spi.AssociationAttributeDefinition;
import org.hibernate.persister.walking.spi.AssociationKey;
import org.hibernate.persister.walking.spi.CollectionDefinition;
import org.hibernate.persister.walking.spi.EntityDefinition;
import org.hibernate.persister.walking.spi.WalkingException;
import org.hibernate.sql.results.graph.FetchOptions;
import org.hibernate.tuple.AbstractNonIdentifierAttribute;
import org.hibernate.tuple.BaselineAttributeInformation;
import org.hibernate.tuple.NonIdentifierAttribute;
import org.hibernate.type.AnyType;
import org.hibernate.type.AssociationType;

/**
 * @author Steve Ebersole
 */
public class CompositeBasedAssociationAttribute
		extends AbstractNonIdentifierAttribute
		implements NonIdentifierAttribute, AssociationAttributeDefinition {

	private final int subAttributeNumber;
	private final AssociationKey associationKey;
	private Joinable joinable;

	public CompositeBasedAssociationAttribute(
			AbstractCompositionAttribute source,
			SessionFactoryImplementor factory,
			int entityBasedAttributeNumber,
			String attributeName,
			AssociationType attributeType,
			BaselineAttributeInformation baselineInfo,
			int subAttributeNumber,
			AssociationKey associationKey) {
		super( source, factory, entityBasedAttributeNumber, attributeName, attributeType, baselineInfo );
		this.subAttributeNumber = subAttributeNumber;
		this.associationKey = associationKey;
	}

	@Override
	public AssociationType getType() {
		return (AssociationType) super.getType();
	}

	@Override
	public AbstractCompositionAttribute getSource() {
		return (AbstractCompositionAttribute) super.getSource();
	}

	protected Joinable getJoinable() {
		if ( joinable == null ) {
			joinable = getType().getAssociatedJoinable( sessionFactory() );
		}
		return joinable;
	}

	@Override
	public AssociationKey getAssociationKey() {
		return associationKey;
	}

	@Override
	public AssociationNature getAssociationNature() {
		if ( getType().isAnyType() ) {
			return AssociationNature.ANY;
		}
		else {
			if ( getJoinable().isCollection() ) {
				return AssociationNature.COLLECTION;
			}
			else {
				return AssociationNature.ENTITY;
			}
		}
	}

	private boolean isAnyType() {
		return getAssociationNature() == AssociationNature.ANY;
	}

	private boolean isEntityType() {
		return getAssociationNature() == AssociationNature.ENTITY;
	}

	private boolean isCollection() {
		return getAssociationNature() == AssociationNature.COLLECTION;
	}

	@Override
	public AnyMappingDefinition toAnyDefinition() {
		if ( !isAnyType() ) {
			throw new WalkingException( "Cannot build AnyMappingDefinition from non-any-typed attribute" );
		}
		// todo : not sure how lazy is propogated into the component for a subattribute of type any
		return new StandardAnyTypeDefinition( (AnyType) getType(), false );
	}

	@Override
	public EntityDefinition toEntityDefinition() {
		if ( isCollection() ) {
			throw new IllegalStateException( "Cannot treat collection attribute as entity type" );
		}
		if ( isAnyType() ) {
			throw new IllegalStateException( "Cannot treat any-type attribute as entity type" );
		}
		return (EntityPersister) getJoinable();
	}

	@Override
	public CollectionDefinition toCollectionDefinition() {
		if ( isEntityType() ) {
			throw new IllegalStateException( "Cannot treat entity attribute as collection type" );
		}
		if ( isAnyType() ) {
			throw new IllegalStateException( "Cannot treat any-type attribute as collection type" );
		}
		return (CollectionPersister) getJoinable();
	}

	@Override
	public FetchOptions determineFetchPlan(LoadQueryInfluencers loadQueryInfluencers, PropertyPath propertyPath) {
		final EntityPersister owningPersister = getSource().locateOwningPersister();

		FetchStyle style = FetchOptionsHelper.determineFetchStyleByProfile(
				loadQueryInfluencers,
				owningPersister,
				propertyPath,
				attributeNumber()
		);
		if ( style == null ) {
			style = determineFetchStyleByMetadata( getFetchMode(), getType() );
		}

		return FetchOptions.valueOf( determineFetchTiming( style ), style );
	}

	protected FetchStyle determineFetchStyleByMetadata(FetchMode fetchMode, AssociationType type) {
		return FetchOptionsHelper.determineFetchStyleByMetadata( fetchMode, type, sessionFactory() );
	}

	private FetchTiming determineFetchTiming(FetchStyle style) {
		return FetchOptionsHelper.determineFetchTiming( style, getType(), sessionFactory() );
	}

	@Override
	public CascadeStyle determineCascadeStyle() {
		return getCascadeStyle();
	}

	private HydratedCompoundValueHandler hydratedCompoundValueHandler;

	@Override
	public HydratedCompoundValueHandler getHydratedCompoundValueExtractor() {
		if ( hydratedCompoundValueHandler == null ) {
			hydratedCompoundValueHandler = new HydratedCompoundValueHandler() {
				@Override
				public Object extract(Object hydratedState) {
					return ( (Object[] ) hydratedState )[ subAttributeNumber ];
				}

				@Override
				public void inject(Object hydratedState, Object value) {
					( (Object[] ) hydratedState )[ subAttributeNumber ] = value;
				}
			};
		}
		return hydratedCompoundValueHandler;
	}

	@Override
	protected String loggableMetadata() {
		return super.loggableMetadata() + ",association";
	}
}
