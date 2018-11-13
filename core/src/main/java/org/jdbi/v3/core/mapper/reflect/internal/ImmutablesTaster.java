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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import org.jdbi.v3.core.config.JdbiConfig;
import org.jdbi.v3.core.generic.GenericTypes;
import org.jdbi.v3.core.mapper.reflect.internal.PojoProperties.PojoProperty;
import org.jdbi.v3.core.statement.UnableToCreateStatementException;

public class ImmutablesTaster implements Function<Type, Optional<? extends PojoProperties<?>>>, JdbiConfig<ImmutablesTaster> {

    private final Map<Class<?>, Function<Type, PojoProperties<?>>> registered = new HashMap<>();

    public ImmutablesTaster() {}

    private ImmutablesTaster(ImmutablesTaster other) {
        registered.putAll(other.registered);
    }

    @Override
    public ImmutablesTaster createCopy() {
        return new ImmutablesTaster(this);
    }

    public <T> void register(Class<T> iface, Class<? extends T> impl, Class<? extends T> modifiable) {
        final Function<Type, PojoProperties<?>> immutable = t -> new ImmutablePojoProperties<>(t, iface, impl);
        registered.put(iface, immutable);
        registered.put(impl, immutable);
        if (modifiable != null) {
            registered.put(modifiable, t -> new ModifiablePojoProperties<>(t, iface, modifiable));
        }
    }

    @Override
    public Optional<? extends PojoProperties<?>> apply(Type t) {
        final Class<?> erased = GenericTypes.getErasedType(t);
        return Stream.concat(Stream.of(erased), Arrays.stream(erased.getInterfaces()))
                .map(registered::get)
                .filter(Objects::nonNull)
                .map(f -> f.apply(t))
                .findAny();
    }

    static MethodHandle alwaysSet() {
        return MethodHandles.dropArguments(MethodHandles.constant(boolean.class, true), 0, Object.class);
    }

    private static <T> T guard(ThrowingSupplier<T> supp) {
        try {
            return supp.get();
        } catch (RuntimeException | Error e) { // NOPMD
            throw e;
        } catch (Throwable t) {
            throw new UnableToCreateStatementException("Couldn't execute Immutables method", t);
        }
    }

    abstract static class BasePojoProperties<T> extends PojoProperties<T> {
        protected final Map<String, ImmutablesPojoProperty<T>> properties = new HashMap<>();
        protected final Class<?> defn;
        protected final Class<?> impl;

        BasePojoProperties(Type type, Class<?> defn, Class<?> impl) {
            super(type);
            this.defn = defn;
            this.impl = impl;
            init();
            for (Method m : defn.getMethods()) {
                if (isProperty(m)) {
                    final String name = m.getName();
                    properties.put(name, createProperty(name, m));
                }
            }
        }

        protected abstract void init();

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

        protected abstract ImmutablesPojoProperty<T> createProperty(String name, Method m);
    }

    static class ImmutablePojoProperties<T> extends BasePojoProperties<T> {
        private Class<?> builderClass;
        private MethodHandle builderFactory;
        private MethodHandle builderBuild;

        ImmutablePojoProperties(Type type, Class<?> defn, Class<?> impl) {
            super(type, defn, impl);
        }

        @Override
        protected void init() {
            try {
                final Method builderMethod = impl.getMethod("builder");
                builderFactory = MethodHandles.lookup().unreflect(builderMethod);
                builderClass = builderFactory.type().returnType();
                builderBuild = MethodHandles.lookup().findVirtual(builderClass, "build", MethodType.methodType(impl));
            } catch (NoSuchMethodException | IllegalAccessException e) {
               throw new IllegalArgumentException("Failed to inspect Immutables " + defn, e);
            }
        }

        @Override
        protected ImmutablesPojoProperty<T> createProperty(String name, Method m) {
            final Type propertyType = GenericTypes.resolveType(m.getGenericReturnType(), getType());
            try {
                return new ImmutablesPojoProperty<T>(
                        name,
                        propertyType,
                        m,
                        alwaysSet(),
                        MethodHandles.lookup().unreflect(m),
                        MethodHandles.lookup().findVirtual(builderClass, name, MethodType.methodType(builderClass, GenericTypes.getErasedType(propertyType))));
            } catch (IllegalAccessException | NoSuchMethodException e) {
                throw new IllegalArgumentException("Failed to inspect method " + m, e);
            }
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

    static class ModifiablePojoProperties<T> extends BasePojoProperties<T> {
        private MethodHandle create;

        ModifiablePojoProperties(Type type, Class<?> defn, Class<?> impl) {
            super(type, defn, impl);
        }

        @Override
        protected void init() {
            try {
                create = MethodHandles.lookup().findStatic(impl, "create", MethodType.methodType(impl));
            } catch (NoSuchMethodException | IllegalAccessException e) {
               throw new IllegalArgumentException("Failed to inspect Immutables " + defn, e);
            }
        }

        @Override
        protected ImmutablesPojoProperty<T> createProperty(String name, Method m) {
            final Type propertyType = GenericTypes.resolveType(m.getGenericReturnType(), getType());
            try {
                return new ImmutablesPojoProperty<T>(
                        name,
                        propertyType,
                        m,
                        isSetMethod(name),
                        MethodHandles.lookup().unreflect(m),
                        MethodHandles.lookup().findVirtual(impl, setterName(name), MethodType.methodType(impl, GenericTypes.getErasedType(propertyType))));
            } catch (IllegalAccessException | NoSuchMethodException e) {
                throw new IllegalArgumentException("Failed to inspect method " + m, e);
            }
        }

        private MethodHandle isSetMethod(String name) {
            try {
                return MethodHandles.lookup().findVirtual(impl, name + "IsSet", MethodType.methodType(boolean.class));
            } catch (NoSuchMethodException e) {
                // not optional field
                return alwaysSet();
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException("Failed to find IsSet method for " + name, e);
            }
        }

        private String setterName(String name) {
            return "set" + name.substring(0, 1).toUpperCase() + name.substring(1);
        }

        @Override
        public PojoBuilder<T> create() {
            final Object instance = guard(create::invoke);
            return new PojoBuilder<T>() {
                @Override
                public void set(String property, Object value) {
                    guard(() -> properties.get(property).setter.invoke(instance, value));
                }

                @SuppressWarnings("unchecked")
                @Override
                public T build() {
                    return (T) instance;
                }
            };
        }
    }

    static class ImmutablesPojoProperty<T> implements PojoProperty<T> {
        private final String name;
        private final Type type;
        private final Method defn;
        private final MethodHandle isSet;
        private final MethodHandle getter;
        final MethodHandle setter;

        ImmutablesPojoProperty(String name, Type type, Method defn, MethodHandle isSet, MethodHandle getter, MethodHandle setter) {
            this.name = name;
            this.type = type;
            this.defn = defn;
            this.isSet = isSet;
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
            return guard(() -> {
                if (Boolean.TRUE.equals(isSet.invoke(pojo))) {
                    return getter.invoke(pojo);
                } else {
                    return null;
                }
            });
        }
    }

    interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }
}
