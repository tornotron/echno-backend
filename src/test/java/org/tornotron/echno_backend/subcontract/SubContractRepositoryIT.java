package org.tornotron.echno_backend.subcontract;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.support.AbstractIntegrationTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link SubContractRepository} against a real CockroachDB
 * (see {@link AbstractIntegrationTest}). Asserts the tenant-scoped lookup and that
 * a persisted subcontract cascades its milestone and shows up in {@code findAll}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SubContractRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private SubContractRepository subContractRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void findByIdAndOrganization_returnsTheSubContractWithItsMilestone() {
        Organization org = persistOrganization("Org A");
        SubContract subContract = persistSubContract(org, "Foundation Works", "BuildCo");
        em.flush();
        em.clear();

        Optional<SubContract> found = subContractRepository.findByIdAndOrganization_Id(subContract.getId(), org.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getContractName()).isEqualTo("Foundation Works");
        assertThat(found.get().getOrganization().getId()).isEqualTo(org.getId());
        assertThat(found.get().getMilestones()).hasSize(1);
        assertThat(found.get().getMilestones().get(0).getName()).isEqualTo("Slab casting");
        assertThat(found.get().getMilestones().get(0).getOrganization().getId()).isEqualTo(org.getId());
    }

    @Test
    void findByIdAndOrganization_doesNotReturnAcrossTenants() {
        Organization orgA = persistOrganization("Org A2");
        Organization orgB = persistOrganization("Org B2");
        SubContract subContract = persistSubContract(orgA, "Plumbing", "PipeCo");
        em.flush();
        em.clear();

        Optional<SubContract> found = subContractRepository.findByIdAndOrganization_Id(subContract.getId(), orgB.getId());

        assertThat(found).isEmpty();
    }

    @Test
    void findAll_paginated_includesThePersistedSubContract() {
        Organization org = persistOrganization("Org C");
        persistSubContract(org, "Electrical Fit-out", "SparkCo");
        em.flush();
        em.clear();

        Page<SubContract> result = subContractRepository.findAll(PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(SubContract::getContractName)
                .contains("Electrical Fit-out");
    }

    private Organization persistOrganization(String name) {
        Organization org = new Organization();
        org.setOrganizationName(name);
        org.setOrganizationAddress(name + " address");
        org.setOrganizationEmail(name.replace(" ", "").toLowerCase() + "@example.test");
        org.setOrganizationPhone("0000000000");
        em.persist(org);
        return org;
    }

    private SubContract persistSubContract(Organization org, String contractName, String contractorName) {
        SubContract subContract = new SubContract();
        subContract.setOrganization(org);
        subContract.setContractName(contractName);
        subContract.setContractorName(contractorName);
        subContract.setStatus("active");
        subContract.setType("labor");

        ContractMilestone milestone = new ContractMilestone();
        milestone.setName("Slab casting");
        milestone.setStatus("pending");
        milestone.setOrganization(org);
        subContract.addMilestone(milestone);

        em.persist(subContract);
        return subContract;
    }
}
