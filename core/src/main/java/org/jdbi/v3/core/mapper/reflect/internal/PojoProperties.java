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
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.generic.GenericType;
import org.slf4j.LoggerFactory;

/**
 * {@link java.beans.Introspector}-like interface that works with arbitrary pojos, not just beans.
 */
public abstract class PojoProperties<T> {
    private static final List<Function<Type, Optional<PojoProperties<?>>>> TASTERS;

    private static final Map<Type, PojoProperties<?>> INSTANCES = ExpiringMap.builder()
            .expiration(10, TimeUnit.MINUTES)
            .expirationPolicy(ExpirationPolicy.ACCESSED)
            .<Type, PojoProperties<?>>entryLoader(PojoProperties::taste)
            .build();

    static {
        final List<Function<Type, Optional<PojoProperties<?>>>> tasters = new ArrayList<>();
        try {
            tasters.add(new ImmutablesTaster());
        } catch (Throwable t) {
            LoggerFactory.getLogger(Jdbi.class).trace("Not loading Immutables support", t);
        }
        tasters.add(new BeanTaster());
        TASTERS = Collections.unmodifiableList(tasters);
    }

    private final Type type;

    protected PojoProperties(Type type) {
        this.type = type;
    }

    @SuppressWarnings("unchecked")
    public static <T> PojoProperties<T> of(Type type) {
        return (PojoProperties<T>) INSTANCES.get(type);
    }

    public static <T> PojoProperties<T> of(GenericType<T> type) {
        return of(type.getType());
    }

    public static <T> PojoProperties<T> of(Class<T> type) {
        return of((Type) type);
    }

    private static PojoProperties<?> taste(Type type) {
        return TASTERS.stream()
                .flatMap(t -> t.apply(type).stream())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Couldn't find properties for " + type));
    }

    public Type getType() {
        return type;
    }

    public abstract Map<String, PojoProperty<T>> getProperties();
    public abstract PojoBuilder<T> create();

    public interface PojoBuilder<T> {
        T build();
    }

    public interface PojoProperty<T> {
        String getName();
        Type getType();
        <A extends Annotation> Optional<A> getAnnotation(Class<A> anno);
        Object get(T pojo);
        void set(PojoBuilder<T> builder, Object value);
    }
}
