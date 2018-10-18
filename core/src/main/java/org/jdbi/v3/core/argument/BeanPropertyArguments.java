/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jdbi.v3.core.argument;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.jdbi.v3.core.mapper.reflect.internal.PojoProperties;
import org.jdbi.v3.core.mapper.reflect.internal.PojoProperties.PojoProperty;
import org.jdbi.v3.core.mapper.reflect.internal.PojoPropertiesFactory;
import org.jdbi.v3.core.statement.StatementContext;

/**
 * Inspect a {@link java.beans} style object and bind parameters
 * based on each of its discovered properties.
 */
public class BeanPropertyArguments extends MethodReturnValueNamedArgumentFinder {
    private final AtomicReference<PojoProperties<?>> properties = new AtomicReference<>();

    /**
     * @param prefix an optional prefix (we insert a '.' as a separator)
     * @param bean the bean to inspect and bind
     */
    public BeanPropertyArguments(String prefix, Object bean) {
        super(prefix, bean);
    }

    @Override
    Optional<TypedValue> getValue(String name, StatementContext ctx) {
        PojoProperties<?> info = properties.get();
        if (info == null) {
            info = ctx.getConfig(PojoPropertiesFactory.class).propertiesOf(object.getClass());
            properties.set(info);
        }
        @SuppressWarnings("unchecked")
        PojoProperty<Object> property = (PojoProperty<Object>) info.getProperties().get(name);

        if (property == null) {
            return Optional.empty();
        }

        return Optional.of(new TypedValue(property.getType(), property.get(object)));
    }

    @Override
    NamedArgumentFinder getNestedArgumentFinder(Object obj) {
        return new BeanPropertyArguments(null, obj);
    }

    @Override
    public String toString() {
        return "{lazy bean property arguments \"" + object + "\"";
    }
}
