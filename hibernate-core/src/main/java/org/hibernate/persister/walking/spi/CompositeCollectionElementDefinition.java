/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.persister.walking.spi;

/**
 * The definition for a composite collection element.
 *
 * @author Gail Badner
 */
public interface CompositeCollectionElementDefinition extends CompositionDefinition{
	/**
	 * Returns the collection definition.
	 * @return the collection definition.
	 */
	CollectionDefinition getCollectionDefinition();
}
