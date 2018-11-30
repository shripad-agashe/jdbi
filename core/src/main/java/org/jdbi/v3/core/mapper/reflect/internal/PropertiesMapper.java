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

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.mapper.RowMapperFactory;
import org.jdbi.v3.core.mapper.reflect.BeanMapper;
import org.jdbi.v3.core.statement.StatementContext;

public class PropertiesMapper<T> extends BeanMapper<T> {
    private PropertiesMapper(Class<T> type, String prefix) {
        super(type, prefix);
    }

    public static RowMapperFactory factory(Class<?> type) {
        return RowMapperFactory.of(type, PropertiesMapper.of(type));
    }

    public static RowMapperFactory factory(Class<?> type, String prefix) {
        return RowMapperFactory.of(type, PropertiesMapper.of(type, prefix));
    }

    public static <T> RowMapper<T> of(Class<T> type) {
        return PropertiesMapper.of(type, DEFAULT_PREFIX);
    }

    public static <T> RowMapper<T> of(Class<T> type, String prefix) {
        return new PropertiesMapper<>(type, prefix);
    }

    @Override
    protected PojoProperties<T> findProperties(StatementContext ctx) {
        return ctx.getConfig(PojoPropertiesFactory.class).propertiesOf(type);
    }
}
