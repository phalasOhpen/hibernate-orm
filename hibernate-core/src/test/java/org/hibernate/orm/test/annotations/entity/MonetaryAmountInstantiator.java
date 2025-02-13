/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.orm.test.annotations.entity;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.function.Supplier;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.spi.EmbeddableInstantiator;

/**
 * @author Steve Ebersole
 */
public class MonetaryAmountInstantiator implements EmbeddableInstantiator {
	@Override
	public Object instantiate(Supplier<Object[]> valuesAccess, SessionFactoryImplementor sessionFactory) {
		final Object[] values = valuesAccess.get();
		final BigDecimal amount = (BigDecimal) values[0];
		final Currency currency = (Currency) values[1];

		if ( amount == null && currency == null ) {
			return null;
		}

		return new MonetaryAmount( amount, currency );
	}

	@Override
	public boolean isInstance(Object object, SessionFactoryImplementor sessionFactory) {
		return object instanceof MonetaryAmount;
	}

	@Override
	public boolean isSameClass(Object object, SessionFactoryImplementor sessionFactory) {
		return MonetaryAmount.class.equals( object.getClass() );
	}
}
