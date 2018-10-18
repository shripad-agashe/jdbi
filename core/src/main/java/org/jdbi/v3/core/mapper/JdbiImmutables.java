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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.config.JdbiConfig;
import org.jdbi.v3.core.generic.GenericTypes;
import org.jdbi.v3.core.mapper.reflect.BeanMapper;
import org.jdbi.v3.core.mapper.reflect.internal.ImmutablesTaster;

/**
 * Configuration class for Jdbi + Immutables support.
 */
public class JdbiImmutables implements JdbiConfig<JdbiImmutables> {
    private final Map<Class<?>, Class<?>> ifaceImpls = new HashMap<>();
    private ConfigRegistry registry;

    public JdbiImmutables() { }

    private JdbiImmutables(JdbiImmutables other) {
        ifaceImpls.putAll(other.ifaceImpls);
    }

    /**
     * Register an Immutables class by its implementation class.
     * @param impl the generated {@code Immutable*} class
     * @return this configuration class, for method chaining
     */
    public JdbiImmutables register(Class<?> impl) {
        if (impl.isInterface()) {
            throw new IllegalArgumentException("Register the implemented Immutable type, not the specifying interface");
        }
        final Class<?> iface = impl.getInterfaces()[0];
        ifaceImpls.put(iface, impl);
        registry.get(ImmutablesTaster.class).register(iface, impl);
        final Optional<RowMapper<?>> mapper = Optional.of(BeanMapper.of(impl));
        registry.get(RowMappers.class).register(new RowMapperFactory() {
            @Override
            public Optional<RowMapper<?>> build(Type type, ConfigRegistry config) {
                final Class<?> raw = GenericTypes.getErasedType(type);
                return raw == iface || raw == impl ? mapper : Optional.empty();
            }
        });
        return this;
    }

    public Class<?> implementationFor(Class<?> iface) {
        return ifaceImpls.get(iface);
    }

    @Override
    public void setRegistry(ConfigRegistry registry) {
        this.registry = registry;
    }

    @Override
    public JdbiImmutables createCopy() {
        return new JdbiImmutables(this);
    }
}
