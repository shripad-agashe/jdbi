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
package org.jdbi.v3.core.mapper;

import java.lang.reflect.Type;
import java.util.Optional;

import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.config.JdbiConfig;
import org.jdbi.v3.core.generic.GenericTypes;
import org.jdbi.v3.core.mapper.reflect.BeanMapper;
import org.jdbi.v3.core.mapper.reflect.internal.ImmutablesTaster;
import org.jdbi.v3.meta.Beta;

/**
 * Configuration class for Jdbi + Immutables support.
 */
@Beta
public class JdbiImmutables implements JdbiConfig<JdbiImmutables> {
    private ConfigRegistry registry;

    public JdbiImmutables() {}

    /**
     * Register an Immutables class by its implementation class.
     * @param defn the interface {@code T} you write
     * @param impl the generated {@code ImmutableT} class
     * @return this configuration class, for method chaining
     */
    public <T> JdbiImmutables register(Class<T> defn, Class<? extends T> impl) {
        return register(defn, impl, null);
    }

    /**
     * Register Immutables and Modifiable classes by their implementation classes.
     * @param defn the interface {@code T} you write
     * @param impl the generated {@code ImmutableT} class
     * @param modifiable the generated {@code ModifiableT} class
     * @return this configuration class, for method chaining
     */
    public <T> JdbiImmutables register(Class<T> defn, Class<? extends T> impl, Class<? extends T> modifiable) {
        if (impl.isInterface()) {
            throw new IllegalArgumentException("Register the implemented Immutable type, not the specifying interface");
        }
        registry.get(ImmutablesTaster.class).register(defn, impl, modifiable);
        final Optional<RowMapper<?>> immutableMapper = Optional.of(BeanMapper.of(impl));
        final Optional<RowMapper<?>> modifiableMapper = Optional.ofNullable(modifiable).map(BeanMapper::of);
        registry.get(RowMappers.class).register(new RowMapperFactory() {
            @Override
            public Optional<RowMapper<?>> build(Type type, ConfigRegistry config) {
                final Class<?> raw = GenericTypes.getErasedType(type);
                if (raw.equals(defn) || raw.equals(impl)) {
                    return immutableMapper;
                }
                if (raw.equals(modifiable)) {
                    return modifiableMapper;
                }
                return Optional.empty();
            }
        });
        return this;
    }

    @Override
    public void setRegistry(ConfigRegistry registry) {
        this.registry = registry;
    }

    @Override
    public JdbiImmutables createCopy() {
        return new JdbiImmutables();
    }
}
