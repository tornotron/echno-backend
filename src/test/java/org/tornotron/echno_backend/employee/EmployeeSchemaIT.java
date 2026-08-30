package org.tornotron.echno_backend.employee;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the shape of the employee table as Liquibase builds it, against a real
 * CockroachDB (see {@link AbstractIntegrationTest}).
 *
 * <p>An employee's reporting line is {@code manager_id}, the self-referencing foreign
 * key behind {@link Employee#getManager()}. The free-text {@code reporting_manager}
 * column it replaced in v1.2 stayed in the schema afterwards with no entity field, DTO
 * field or query behind it, so it read as a second answer to the same question while
 * holding nothing. Dropping it is what this asserts, together with the column that does
 * carry the relationship still being there.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EmployeeSchemaIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager em;

    @Test
    void employeeTable_hasManagerId_andNoReportingManagerColumn() {
        List<String> columns = employeeColumns();

        assertThat(columns).contains("manager_id");
        assertThat(columns).doesNotContain("reporting_manager");
    }

    @SuppressWarnings("unchecked")
    private List<String> employeeColumns() {
        return em.createNativeQuery(
                        "SELECT lower(column_name) FROM information_schema.columns "
                                + "WHERE lower(table_name) = 'employee' "
                                + "AND table_schema = current_schema()")
                .getResultList();
    }
}
