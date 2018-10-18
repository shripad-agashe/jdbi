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

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Types;

import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.core.statement.StatementContextAccess;
import org.jdbi.v3.core.statement.UnableToCreateStatementException;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

public class TestBeanArguments {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    PreparedStatement stmt;

    StatementContext ctx = StatementContextAccess.createContext();

    @Test
    public void testBindBare() throws Exception {
        new BeanPropertyArguments("", new BindBare())
            .find("foo", ctx)
            .get()
            .apply(5, stmt, null);

        verify(stmt).setBigDecimal(5, BigDecimal.ONE);
    }

    public static final class BindBare {
        public BigDecimal getFoo() {
            return BigDecimal.ONE;
        }
    }

    @Test
    public void testBindNull() throws Exception {
        new BeanPropertyArguments("", new BindNull())
            .find("foo", ctx)
            .get()
            .apply(3, stmt, null);

        verify(stmt).setNull(3, Types.NUMERIC);
    }

    public static final class BindNull {
        public BigDecimal getFoo() {
            return null;
        }
    }

    @Test
    public void testBindPrefix() throws Exception {
        new BeanPropertyArguments("foo", new BindPrefix())
            .find("foo.bar", ctx)
            .get()
            .apply(3, stmt, null);

        verify(stmt).setString(3, "baz");
    }

    public static final class BindPrefix {
        public String getBar() {
            return "baz";
        }
    }

    @Test
    public void testBindIllegalAccess() {
        assertThatThrownBy(() ->
            new BeanPropertyArguments("foo", new BindIllegalAccess())
                .find("foo.bar", ctx))
            .isInstanceOf(UnableToCreateStatementException.class);
    }

    public static final class BindIllegalAccess {
        public String getBar() throws IllegalAccessException {
            throw new IllegalAccessException("Normally the JVM throws this but just for testing...");
        }
    }

    @Test
    public void testBindNoGetter() {
        assertThatThrownBy(() ->
            new BeanPropertyArguments("foo", new BindNoGetter())
                .find("foo.bar", ctx))
            .isInstanceOf(UnableToCreateStatementException.class);
    }

    public static final class BindNoGetter {
        @SuppressWarnings("unused")
        public void setBar(String bar) {}
    }

    @Test
    public void testBindNonPublicGetter() {
        assertThatThrownBy(() ->
            new BeanPropertyArguments("foo", new BindNonPublicGetter())
                .find("foo.bar", ctx))
            .isInstanceOf(UnableToCreateStatementException.class);
    }

    public static final class BindNonPublicGetter {
        protected String getBar() {
            return "baz";
        }

        @SuppressWarnings("unused")
        public void setBar(String bar) {}
    }

    @Test
    public void testBindNestedOptionalNull() throws Exception {
        new BeanPropertyArguments("", new BindNestedOptionalNull())
            .find("foo?.id", ctx)
            .get()
            .apply(3, stmt, null);

        verify(stmt).setNull(3, Types.OTHER);
    }

    public static final class BindNestedOptionalNull {
        public Object getFoo() {
            return null;
        }
    }

    @Test
    public void testBindNestedNestedOptionalNull() throws Exception {
        Object bean = new BindNestedNestedOptionalNull();

        new BeanPropertyArguments("", bean)
            .find("foo?.bar.id", ctx)
            .get()
            .apply(3, stmt, null);

        verify(stmt).setNull(3, Types.OTHER);
    }

    public static final class BindNestedNestedOptionalNull {
        public Object getFoo() {
            return null;
        }
    }

    @Test
    public void testBindNestedNestedNull() {
        assertThatThrownBy(() ->
            new BeanPropertyArguments("", new BindNestedNestedNull())
                .find("foo.bar.id", ctx)
                .get()
                .apply(3, stmt, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    public static final class BindNestedNestedNull {
        public Object getFoo() {
            return null;
        }
    }

    @Test
    public void testBindNestedNestedWrongOptionalNull1() {
        assertThatThrownBy(() ->
            new BeanPropertyArguments("", new BindNestedNestedWrongOptionalNull1())
                .find("foo.bar?.id", ctx)
                .get()
                .apply(3, stmt, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    public static final class BindNestedNestedWrongOptionalNull1 {
        public Object getFoo() {
            return null;
        }
    }

    @Test
    public void testBindNestedNestedWrongOptionalNull2() {
        assertThatThrownBy(() ->
            new BeanPropertyArguments("", new BindNestedNestedWrongOptional2())
                .find("foo.bar.?id", ctx)
                .get()
                .apply(3, stmt, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    public static final class BindNestedNestedWrongOptional2 {
        public Object getFoo() {
            return null;
        }
    }

    @Test
    public void testBindNestedOptionalNonNull() throws Exception {
        new BeanPropertyArguments("", new BindNestedOptionalNonNull())
            .find("foo?.id", ctx)
            .get()
            .apply(3, stmt, null);

        verify(stmt).setLong(3, 69);
    }

    public static final class BindNestedOptionalNonNull {
        public static final class NestedObject {
            public long getId() {
                return 69;
            }
        }

        public Object getFoo() {
            return new NestedObject();
        }
    }

    @Test
    public void testPrivateClass() throws Exception {
        new ObjectMethodArguments(null, Person.create("hello")).find("name", ctx).get().apply(4, stmt, null);
        verify(stmt).setString(4, "hello");
    }

    @Test
    public void testPrivateInterfaceClass() throws Exception {
        new ObjectMethodArguments(null, Car.create("hello")).find("name", ctx).get().apply(4, stmt, null);
        verify(stmt).setString(4, "hello");
    }

    public abstract static class Person {
        public static Person create(String name) {
            return new PersonImpl(name);
        }

        public abstract String name();

        private static class PersonImpl extends Person {
            private String name;

            PersonImpl(String name) {
                this.name = name;
            }

            @Override
            public String name() {
                return name;
            }
        }
    }

    public interface Car {
        static Car create(String name) {
            return new CarImpl(name);
        }

        String name();
    }

    private static class CarImpl implements Car {
        private String name;

        CarImpl(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }
    }
}
