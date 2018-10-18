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

import java.util.Arrays;

import org.immutables.value.Value;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.generic.GenericType;
import org.jdbi.v3.core.rule.H2DatabaseRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ImmutablesTest {
    @Rule
    public H2DatabaseRule dbRule = new H2DatabaseRule();

    private Handle h;

    @Before
    public void setup() {
        h = dbRule.getSharedHandle();
        h.execute("create table immutables (t int, x varchar)");

        h.getConfig(JdbiImmutables.class).register(ImmutableSubValue.class);
    }

    @Test
    public void simpleTest() {
        assertThat(
            h.createUpdate("insert into immutables(t, x) values (:t, :x)")
                .bindBean(ImmutableSubValue.<String, Integer>builder().t(42).x("foo").build())
                .execute())
            .isEqualTo(1);

        assertThat(
            h.createQuery("select * from immutables")
                .mapTo(new GenericType<SubValue<String, Integer>>() {})
                .findOnly())
            .extracting("t", "x")
            .isEqualTo(Arrays.asList(42, "foo"));
    }

    public interface BaseValue<T> {
        T t();
    }

    @Value.Immutable
    public interface SubValue<X, T> extends BaseValue<T> {
        X x();
    }
}
