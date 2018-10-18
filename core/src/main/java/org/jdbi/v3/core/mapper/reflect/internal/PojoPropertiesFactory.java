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
package org.jdbi.v3.core.mapper.reflect.internal;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.config.JdbiConfig;
import org.jdbi.v3.core.generic.GenericType;
import org.slf4j.LoggerFactory;

public class PojoPropertiesFactory implements JdbiConfig<PojoPropertiesFactory> {
    private final List<Function<Type, Optional<PojoProperties<?>>>> tasters = new ArrayList<>();

    private final Map<Type, PojoProperties<?>> instances = ExpiringMap.builder()
            .expiration(10, TimeUnit.MINUTES)
            .expirationPolicy(ExpirationPolicy.ACCESSED)
            .<Type, PojoProperties<?>>entryLoader(this::taste)
            .build();

    private boolean defaultInit = false;

    public PojoPropertiesFactory() {
        defaultInit = true;
    }

    @Override
    public void setRegistry(ConfigRegistry registry) {
        if (defaultInit) {
            // delay construction until we can 'inject' dependencies
            try {
                tasters.add(registry.get(ImmutablesTaster.class));
            } catch (Throwable t) {
                LoggerFactory.getLogger(Jdbi.class).trace("Not loading Immutables support", t);
            }
            tasters.add(new BeanTaster());
        }
    }

    private PojoPropertiesFactory(PojoPropertiesFactory other) {
        tasters.addAll(other.tasters);
    }

    @Override
    public PojoPropertiesFactory createCopy() {
        return new PojoPropertiesFactory(this);
    }

    @SuppressWarnings("unchecked")
    public <T> PojoProperties<T> propertiesOf(Type type) {
        return (PojoProperties<T>) instances.get(type);
    }

    public <T> PojoProperties<T> propertiesOf(GenericType<T> type) {
        return propertiesOf(type.getType());
    }

    public <T> PojoProperties<T> propertiesOf(Class<T> type) {
        return propertiesOf((Type) type);
    }

    private PojoProperties<?> taste(Type type) {
        return tasters.stream()
                .flatMap(t -> t.apply(type).stream())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Couldn't find properties for " + type));
    }
}
