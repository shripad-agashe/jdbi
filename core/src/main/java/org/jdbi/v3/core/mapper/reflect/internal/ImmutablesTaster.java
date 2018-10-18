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

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.immutables.value.Value;
import org.jdbi.v3.core.config.JdbiConfig;
import org.jdbi.v3.core.generic.GenericTypes;
import org.jdbi.v3.core.mapper.reflect.internal.PojoProperties.PojoProperty;
import org.jdbi.v3.core.statement.UnableToCreateStatementException;

public class ImmutablesTaster implements Function<Type, Optional<PojoProperties<?>>>, JdbiConfig<ImmutablesTaster> {

    static {
        Value.Immutable.class.toString(); // Fail fast
    }

    private Set<Class<?>> registered = new HashSet<>();

    public ImmutablesTaster() {}

    private ImmutablesTaster(ImmutablesTaster other) {
        registered.addAll(other.registered);
    }

    @Override
    public ImmutablesTaster createCopy() {
        return new ImmutablesTaster(this);
    }

    public void register(Class<?> iface, Class<?> impl) {
        registered.add(iface);
        registered.add(impl);
    }

    @Override
    public Optional<PojoProperties<?>> apply(Type t) {
        final Optional<Class<?>> defn =
            Arrays.stream(GenericTypes.getErasedType(t).getInterfaces())
            .filter(
                i -> i.getAnnotation(Value.Immutable.class) != null
                  || i.getAnnotation(Value.Modifiable.class) != null
                  || registered.contains(i))
            .findAny();
        if (!defn.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(new ImmutablesPojoProperties<>(defn.get(), GenericTypes.getErasedType(t)));
    }

    private static <T> T guard(ThrowingSupplier<T> supp) {
        try {
            return supp.get();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new UnableToCreateStatementException("Couldn't execute Immutables method", t);
        }
    }

    class ImmutablesPojoProperties<T> extends PojoProperties<T> {
        private final Class<?> builderClass;
        private final MethodHandle builderFactory;
        private final MethodHandle builderBuild;
        private final Map<String, ImmutablesPojoProperty<T>> properties = new HashMap<>();

        ImmutablesPojoProperties(Class<?> defn, Class<?> impl) {
            super(defn);
            try {
                final Method builderMethod = impl.getMethod("builder");
                builderFactory = MethodHandles.lookup().unreflect(builderMethod);
                builderClass = builderFactory.type().returnType();
                builderBuild = MethodHandles.lookup().findVirtual(builderClass, "build", MethodType.methodType(impl));
                for (Method m : defn.getMethods()) {
                    if (isProperty(m)) {
                        final String name = m.getName();
                        final Type propertyType = m.getGenericReturnType();
                        properties.put(name, new ImmutablesPojoProperty<T>(
                                name,
                                propertyType,
                                m,
                                MethodHandles.lookup().unreflect(m),
                                MethodHandles.lookup().findVirtual(builderClass, name, MethodType.methodType(builderClass, GenericTypes.getErasedType(propertyType)))));
                    }
                }
            } catch (NoSuchMethodException | IllegalAccessException e) {
               throw new IllegalArgumentException("Failed to inspect Immutables " + defn, e);
            }
        }

        private boolean isProperty(Method m) {
            return m.getParameterCount() == 0
                && !m.isSynthetic()
                && !Modifier.isStatic(m.getModifiers())
                && m.getDeclaringClass() != Object.class;
        }

        @Override
        public Map<String, ? extends PojoProperty<T>> getProperties() {
            return properties;
        }

        @Override
        public PojoBuilder<T> create() {
            final Object builder = guard(builderFactory::invoke);
            return new PojoBuilder<T>() {
                @Override
                public void set(String property, Object value) {
                    guard(() -> properties.get(property).setter.invoke(builder, value));
                }

                @SuppressWarnings("unchecked")
                @Override
                public T build() {
                    return (T) guard(() -> builderBuild.invoke(builder));
                }
            };
        }
    }

    class ImmutablesPojoProperty<T> implements PojoProperty<T> {
        private final String name;
        private final Type type;
        private final Method defn;
        private final MethodHandle getter;
        final MethodHandle setter;

        ImmutablesPojoProperty(String name, Type type, Method defn, MethodHandle getter, MethodHandle setter) {
            this.name = name;
            this.type = type;
            this.defn = defn;
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Type getType() {
            return type;
        }

        @Override
        public <A extends Annotation> Optional<A> getAnnotation(Class<A> anno) {
            return Optional.ofNullable(defn.getAnnotation(anno));
        }

        @Override
        public Object get(T pojo) {
            return guard(() -> getter.invoke(pojo));
        }
    }

    interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }
}
