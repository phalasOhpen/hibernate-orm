/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.sqm.tree;

import java.util.Set;

import org.hibernate.query.criteria.JpaQueryableCriteria;
import org.hibernate.query.sqm.SqmQuerySource;
import org.hibernate.query.sqm.tree.expression.SqmParameter;

/**
 * The basic SQM statement contract for top-level statements
 *
 * @author Steve Ebersole
 */
public interface SqmStatement<T> extends SqmQuery<T>, JpaQueryableCriteria<T>, SqmVisitableNode {
	SqmQuerySource getQuerySource();
	Set<SqmParameter<?>> getSqmParameters();
}