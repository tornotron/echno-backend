package org.tornotron.echno_backend.siteTransfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "What arrived at the receiving site against a transfer that is in transit. "
        + "Only the quantities are in this payload: who confirmed the delivery is taken from the "
        + "session, not from the request, so a receipt is the caller's own statement and cannot "
        + "be filed under a colleague's name.")
@Data
public class SiteTransferReceiptDto {

    @Schema(description = "Date and time the delivery was taken. Optional; the moment the receipt "
            + "is filed is used when it is left out. It dates the inbound movements this receipt "
            + "writes.", example = "2026-01-17T14:05:00")
    private LocalDateTime receivedOn;

    @Schema(description = "Set when the delivery really did carry more than the transfer sent. "
            + "Without it an over-receipt is refused and the refusal names the line and the "
            + "figures, which is enough to recognise a typed digit. With it the excess is "
            + "recorded and the stock is posted, because material standing in the yard is there "
            + "whether the system likes it or not and a receipt that cannot be filed leaves it "
            + "outside the ledger. A short delivery needs no such flag: recording less than was "
            + "sent puts nothing false into the ledger and leaves an open variance for a stock "
            + "adjustment to close.", example = "false")
    private Boolean allowOverReceipt;

    @Schema(description = "Optional note about the delivery, carried onto the inbound movements "
            + "and the transfer's status trail.", example = "Two bags split in transit, driver informed")
    @Size(max = 500, message = "remarks cannot exceed 500 characters")
    private String remarks;

    @Schema(description = "One entry per transfer line being confirmed. Lines left out of the "
            + "payload are untouched, so a delivery that answers only part of a transfer names "
            + "only the lines it answers.")
    @NotEmpty(message = "items list cannot be empty")
    @Valid
    private List<SiteTransferReceiptLineDto> items;
}
