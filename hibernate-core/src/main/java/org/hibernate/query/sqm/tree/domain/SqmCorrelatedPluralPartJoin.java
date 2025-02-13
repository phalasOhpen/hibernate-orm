/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.sqm.tree.domain;

import org.hibernate.query.sqm.tree.SqmCopyContext;
import org.hibernate.query.sqm.tree.SqmJoinType;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmRoot;

/**
 * @author Christian Beikov
 */
public class SqmCorrelatedPluralPartJoin<O, T> extends SqmPluralPartJoin<O, T> implements SqmCorrelation<O, T> {

	private final SqmCorrelatedRootJoin<O> correlatedRootJoin;
	private final SqmPluralPartJoin<O, T> correlationParent;

	public SqmCorrelatedPluralPartJoin(SqmPluralPartJoin<O, T> correlationParent) {
		super(
				(SqmFrom<?, O>) correlationParent.getLhs(),
				correlationParent.getReferencedPathSource(),
				null,
				SqmJoinType.INNER,
				correlationParent.nodeBuilder()
		);
		this.correlatedRootJoin = SqmCorrelatedRootJoin.create( correlationParent, this );
		this.correlationParent = correlationParent;
	}

	@Override
	public SqmCorrelatedPluralPartJoin<O, T> copy(SqmCopyContext context) {
		final SqmCorrelatedPluralPartJoin<O, T> existing = context.getCopy( this );
		if ( existing != null ) {
			return existing;
		}
		final SqmCorrelatedPluralPartJoin<O, T> path = context.registerCopy(
				this,
				new SqmCorrelatedPluralPartJoin<>( correlationParent.copy( context ) )
		);
		copyTo( path, context );
		return path;
	}

	@Override
	public SqmPluralPartJoin<O, T> getCorrelationParent() {
		return correlationParent;
	}

	@Override
	public SqmPath<T> getWrappedPath() {
		return correlationParent;
	}

	@Override
	public boolean isCorrelated() {
		return true;
	}

	@Override
	public SqmRoot<O> getCorrelatedRoot() {
		return correlatedRootJoin;
	}
}
