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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;

import org.jdbi.v3.core.generic.GenericTypes;
import org.jdbi.v3.core.mapper.reflect.internal.PojoProperties.PojoProperty;
import org.jdbi.v3.core.qualifier.QualifiedType;
import org.jdbi.v3.core.qualifier.Qualifiers;

import static org.jdbi.v3.core.internal.Throwables.throwingOnlyUnchecked;

public class ImmutablesPropertiesFactory {

    private static final Map<Type, PojoProperties<?>> IMMUTABLE_PROPERTIES = ExpiringMap
            .builder()
            .expiration(10, TimeUnit.MINUTES)
            .expirationPolicy(ExpirationPolicy.ACCESSED)
            .build();

    private static final Map<Type, PojoProperties<?>> MODIFIABLE_PROPERTIES = ExpiringMap
            .builder()
            .expiration(10, TimeUnit.MINUTES)
            .expirationPolicy(ExpirationPolicy.ACCESSED)
            .build();

    private ImmutablesPropertiesFactory() {}

    @SuppressWarnings("unchecked")
    public static <T, B> Function<Type, PojoProperties<T>> immutable(Class<T> defn, Supplier<B> builder) {
        return t -> (PojoProperties<T>) IMMUTABLE_PROPERTIES.computeIfAbsent(t, x -> new ImmutablePojoProperties<>(t, defn, builder));
    }

    @SuppressWarnings("unchecked")
    public static <T, M extends T> Function<Type, PojoProperties<T>> modifiable(Class<T> defn, Supplier<M> constructor) {
        return t -> (PojoProperties<T>) MODIFIABLE_PROPERTIES.computeIfAbsent(t, x -> new ModifiablePojoProperties<>(t, defn, constructor));
    }

    static MethodHandle alwaysSet() {
        return MethodHandles.dropArguments(MethodHandles.constant(boolean.class, true), 0, Object.class);
    }

    abstract static class BasePojoProperties<T, B> extends PojoProperties<T> {
        protected final Map<String, ImmutablesPojoProperty<T>> properties = new HashMap<>();
        protected final Class<?> defn;
        protected final Supplier<?> builder;

        BasePojoProperties(Type type, Class<?> defn, Supplier<B> builder) {
            super(type);
            this.defn = defn;
            this.builder = builder;
            for (Method m : defn.getMethods()) {
                if (isProperty(m)) {
                    final String name = m.getName();
                    properties.put(name, createProperty(name, m));
                }
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

        protected abstract ImmutablesPojoProperty<T> createProperty(String name, Method m);
    }

    static class ImmutablePojoProperties<T, B> extends BasePojoProperties<T, B> {
        private MethodHandle builderBuild;

        ImmutablePojoProperties(Type type, Class<?> defn, Supplier<B> builder) {
            super(type, defn, builder);
            try {
                builderBuild = MethodHandles.lookup().unreflect(builder.get().getClass().getMethod("build"));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new IllegalArgumentException("Failed to inspect Immutables " + defn, e);
            }
        }
        @Override
        protected ImmutablesPojoProperty<T> createProperty(String name, Method m) {
            final Class<?> builderClass = builder.get().getClass();
            try {
                final Type propertyType = GenericTypes.resolveType(m.getGenericReturnType(), getType());
                return new ImmutablesPojoProperty<T>(
                        name,
                        QualifiedType.of(propertyType, Qualifiers.getQualifiers(m)),
                        m,
                        alwaysSet(),
                        MethodHandles.lookup().unreflect(m),
                        findBuilderSetter(builderClass, name, propertyType));
            } catch (IllegalAccessException | NoSuchMethodException e) {
                throw new IllegalArgumentException("Failed to inspect method " + m, e);
            }
        }

        private MethodHandle findBuilderSetter(final Class<?> builderClass, String name, Type type)
        throws IllegalAccessException, NoSuchMethodException {
            final NoSuchMethodException failure;
            try {
                return MethodHandles.lookup().unreflect(builderClass.getMethod(name, GenericTypes.getErasedType(type)));
            } catch (NoSuchMethodException e) {
                failure = e;
            }
            for (Method m : builderClass.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1) {
                    return MethodHandles.lookup().unreflect(m);
                }
            }
            throw failure;
        }

        @Override
        public PojoBuilder<T> create() {
            final Object b = builder.get();
            return new PojoBuilder<T>() {
                @Override
                public void set(String property, Object value) {
                    throwingOnlyUnchecked(() -> properties.get(property).setter.invoke(b, value));
                }

                @SuppressWarnings("unchecked")
                @Override
                public T build() {
                    return (T) throwingOnlyUnchecked(() -> builderBuild.invoke(b));
                }
            };
        }
    }

    static class ModifiablePojoProperties<T, M extends T> extends BasePojoProperties<T, M> {
        ModifiablePojoProperties(Type type, Class<?> defn, Supplier<M> constructor) {
            super(type, defn, constructor);
        }

        @Override
        protected ImmutablesPojoProperty<T> createProperty(String name, Method m) {
            final Class<M> impl = implClass();
            final Type propertyType = GenericTypes.resolveType(m.getGenericReturnType(), getType());
            try {
                return new ImmutablesPojoProperty<T>(
                        name,
                        QualifiedType.of(propertyType, Qualifiers.getQualifiers(m)),
                        m,
                        isSetMethod(impl, name),
                        MethodHandles.lookup().unreflect(m),
                        MethodHandles.lookup().findVirtual(impl, setterName(name), MethodType.methodType(impl, GenericTypes.getErasedType(propertyType))));
            } catch (IllegalAccessException | NoSuchMethodException e) {
                throw new IllegalArgumentException("Failed to inspect method " + m, e);
            }
        }

        @SuppressWarnings("unchecked")
        private Class<M> implClass() {
            return (Class<M>) builder.get().getClass();
        }

        private MethodHandle isSetMethod(Class<M> impl, String name) {
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
            final Object instance = builder.get();
            return new PojoBuilder<T>() {
                @Override
                public void set(String property, Object value) {
                    throwingOnlyUnchecked(() -> properties.get(property).setter.invoke(instance, value));
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
        private final QualifiedType type;
        private final Method defn;
        private final MethodHandle isSet;
        private final MethodHandle getter;
        final MethodHandle setter;

        ImmutablesPojoProperty(String name, QualifiedType type, Method defn, MethodHandle isSet, MethodHandle getter, MethodHandle setter) {
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
        public QualifiedType getQualifiedType() {
            return type;
        }

        @Override
        public <A extends Annotation> Optional<A> getAnnotation(Class<A> anno) {
            return Optional.ofNullable(defn.getAnnotation(anno));
        }

        @Override
        public Object get(T pojo) {
            return throwingOnlyUnchecked(() -> {
                if (Boolean.TRUE.equals(isSet.invoke(pojo))) {
                    return getter.invoke(pojo);
                } else {
                    return null;
                }
            });
        }
    }
}
