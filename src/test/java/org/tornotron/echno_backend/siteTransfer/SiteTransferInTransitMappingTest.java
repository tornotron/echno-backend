package org.tornotron.echno_backend.siteTransfer;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.tornotron.echno_backend.material.Material;
import org.tornotron.echno_backend.siteTransfer.dto.SiteTransferItemDto;
import org.tornotron.echno_backend.siteTransfer.mapper.SiteTransferMapper;
import org.tornotron.echno_backend.siteTransferItem.SiteTransferItem;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reporting consequence of splitting a transfer into two steps, stated on the line itself.
 *
 * <p>An organization-wide total of on-hand stock is now short by everything in transit, because
 * a quantity that has left one site and not been confirmed at another is on neither balance.
 * That is the truth about material on a lorry, and it has to be visible somewhere rather than
 * being discovered as a discrepancy: a report summing stock across projects reads this figure to
 * show a labelled in-transit total beside the on-hand one. On a transfer that has been received
 * the same figure is the open variance a stock adjustment closes.
 */
class SiteTransferInTransitMappingTest {

    private final SiteTransferMapper mapper = Mappers.getMapper(SiteTransferMapper.class);

    private SiteTransferItem line(int sent, Integer received) {
        Material material = new Material();
        material.setId(2L);
        SiteTransferItem item = new SiteTransferItem();
        item.setId(84L);
        item.setMaterial(material);
        item.setSentQuantity(sent);
        item.setReceivedQuantity(received);
        return item;
    }

    @Test
    void aLineNobodyHasConfirmedReportsTheWholeSentQuantityAsInTransit() {
        SiteTransferItemDto dto = mapper.toItemDto(line(10, null));

        assertThat(dto.getReceivedQuantity()).isNull();
        assertThat(dto.getInTransitQuantity()).isEqualTo(10);
    }

    @Test
    void aShortDeliveryReportsTheGapThatIsStillUnaccountedFor() {
        SiteTransferItemDto dto = mapper.toItemDto(line(10, 8));

        assertThat(dto.getReceivedQuantity()).isEqualTo(8);
        assertThat(dto.getInTransitQuantity()).isEqualTo(2);
    }

    @Test
    void aLineReceivedInFullReportsNothingInTransit() {
        assertThat(mapper.toItemDto(line(10, 10)).getInTransitQuantity()).isZero();
    }

    /** A negative amount on a lorry is not a thing, so an acknowledged excess floors at zero. */
    @Test
    void anAcknowledgedOverReceiptReportsNothingInTransitRatherThanANegativeAmount() {
        assertThat(mapper.toItemDto(line(10, 12)).getInTransitQuantity()).isZero();
    }
}
