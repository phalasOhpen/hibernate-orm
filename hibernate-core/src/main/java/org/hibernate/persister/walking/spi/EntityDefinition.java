/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.persister.walking.spi;

import org.hibernate.persister.entity.EntityPersister;

/**
 * Defines the contract for walking the attributes defined by an entity
 *
 * @author Steve Ebersole
 */
public interface EntityDefinition extends AttributeSource {
	EntityPersister getEntityPersister();
	EntityIdentifierDefinition getEntityKeyDefinition();
}
