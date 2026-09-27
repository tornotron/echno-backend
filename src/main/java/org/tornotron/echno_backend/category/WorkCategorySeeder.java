package org.tornotron.echno_backend.category;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantEntityHelper;
import org.tornotron.echno_backend.organization.Organization;

import java.util.List;

/**
 * Seeds the standard construction work categories into an organization, so the Work Category
 * dropdown on a task is populated from the first day rather than empty until someone types one in.
 *
 * <p>The list is the one the product team supplied (28 categories, from preliminaries and site
 * setup through to handover). It is additive: a category whose normalized name the organization
 * already holds is skipped, so an organization's own entries are never duplicated or overwritten,
 * and a rerun inserts nothing. Changeset {@code 127-backfill-standard-work-categories} applies the
 * same list to organizations that existed before this seeder did.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkCategorySeeder {

    /** A standard category: the name shown in the dropdown and its one-line description. */
    record StandardCategory(String name, String description) {}

    /** In site order, which is also the order they appear in the dropdown. */
    static final List<StandardCategory> STANDARD_CATEGORIES = List.of(
            new StandardCategory("Preliminaries & Site Setup",
                    "Initial activities required to mobilize, establish, survey, secure, and prepare the construction site for project execution."),
            new StandardCategory("Earthwork",
                    "Activities involving site clearing, excavation, earth cutting, soil removal, filling, backfilling, compaction, grading, and leveling."),
            new StandardCategory("Foundation Works",
                    "Construction of foundations and associated works, including PCC, footings, rafts, piles, pile caps, waterproofing, and backfilling."),
            new StandardCategory("Concrete Works",
                    "Production, placement, pumping, finishing, and curing of structural and non-structural concrete, including RCC and ready-mix concrete."),
            new StandardCategory("Reinforcement Works",
                    "Fabrication and installation of steel reinforcement, including cutting, bending, fixing, mesh, couplers, and binding."),
            new StandardCategory("Formwork / Shuttering",
                    "Temporary mould and support systems used to form concrete elements such as footings, columns, beams, slabs, walls, and stairs."),
            new StandardCategory("Structural Steel Works",
                    "Fabrication, erection, connection, welding, bolting, coating, and decking activities associated with structural steel construction."),
            new StandardCategory("Masonry Works",
                    "Construction of brick, block, AAC, stone, partition, internal, and external wall systems."),
            new StandardCategory("Plastering & Rendering",
                    "Application of plaster, render, and related surface treatments to internal, external, ceiling, and repaired surfaces."),
            new StandardCategory("Waterproofing",
                    "Installation of systems that protect roofs, toilets, basements, terraces, tanks, and joints from water ingress."),
            new StandardCategory("Roofing",
                    "Construction and finishing of roof systems including RCC, metal, tile, sheet roofing, insulation, and drainage."),
            new StandardCategory("Flooring",
                    "Installation of floor finishes including PCC, tiles, vitrified tiles, ceramic, granite, marble, stone, industrial, epoxy, and vinyl flooring."),
            new StandardCategory("Wall Finishes",
                    "Application and installation of wall surface finishes such as tiles, ceramic finishes, stone cladding, paint, wallpaper, and decorative treatments."),
            new StandardCategory("Ceiling Works",
                    "Installation and finishing of false, gypsum, grid, and metal ceilings, including insulation and ceiling painting."),
            new StandardCategory("Doors & Windows",
                    "Supply and installation of doors, windows, curtain walls, glazing systems, and associated hardware and fittings."),
            new StandardCategory("Painting Works",
                    "Surface preparation and application of primer, putty, interior and exterior paints, enamel, protective, and waterproof coatings."),
            new StandardCategory("Plumbing",
                    "Installation of water supply, drainage, sanitary piping, pumps, tanks, sanitary fixtures, and plumbing fixtures."),
            new StandardCategory("Electrical Works",
                    "Installation of electrical conduits, wiring, cables, distribution boards, switches, sockets, lighting, earthing, lightning protection, and generator connections."),
            new StandardCategory("HVAC",
                    "Installation and commissioning of heating, ventilation, and air-conditioning systems including ducting, piping, AHUs, FCUs, VRF/VRV, ventilation, and controls."),
            new StandardCategory("Fire & Life Safety",
                    "Installation of fire protection and life-safety systems including hydrants, sprinklers, alarms, extinguishers, pumps, fire-rated doors, emergency lighting, and fire stopping."),
            new StandardCategory("ELV / Low Voltage Systems",
                    "Installation of low-voltage and building technology systems such as CCTV, access control, networking, structured cabling, public address, intercom, BMS, and security systems."),
            new StandardCategory("External Development",
                    "Development of external site infrastructure including roads, paving, footpaths, kerbs, parking, drainage, boundary walls, gates, and landscaping."),
            new StandardCategory("Utilities & Infrastructure",
                    "Installation of site and project utility infrastructure including water, sewerage, stormwater, electrical systems, transformers, DG systems, and utility trenches."),
            new StandardCategory("Specialized Works",
                    "Specialized building and industrial systems including lifts, escalators, solar systems, building automation, acoustic works, clean rooms, and specialized installations."),
            new StandardCategory("Testing, Commissioning & Handover",
                    "Inspection, testing, commissioning, snagging, rectification, documentation, and final handover of completed construction works and systems."),
            new StandardCategory("Demolition & Renovation",
                    "Removal, dismantling, repair, renovation, retrofitting, restoration, and debris clearance associated with existing structures."),
            new StandardCategory("Site Logistics",
                    "Management and movement of materials, equipment, machinery, lifting operations, storage, unloading, internal transportation, and site waste."),
            new StandardCategory("Safety & Environmental",
                    "Activities supporting construction safety and environmental management, including PPE, fall protection, dust and noise control, waste disposal, monitoring, and housekeeping.")
    );

    private final CategoryRepository categoryRepository;
    private final TenantEntityHelper tenantEntityHelper;

    /**
     * Adds every standard category the current organization does not already hold.
     *
     * @return How many categories were created.
     */
    @Transactional
    public int seedDefaults() {
        Organization organization = tenantEntityHelper.resolveCurrentOrganization();
        int created = 0;
        for (StandardCategory standard : STANDARD_CATEGORIES) {
            String normalized = CategoryNormalizer.normalize(standard.name());
            if (categoryRepository.existsByNormalizedNameAndOrganization_Id(normalized, organization.getId())) {
                continue;
            }
            Category category = new Category();
            category.setOrganization(organization);
            category.setName(standard.name());
            category.setNormalizedName(normalized);
            category.setDescription(standard.description());
            categoryRepository.save(category);
            created++;
        }
        log.info("Seeded {} standard work categories for organization {}", created, organization.getId());
        return created;
    }
}
