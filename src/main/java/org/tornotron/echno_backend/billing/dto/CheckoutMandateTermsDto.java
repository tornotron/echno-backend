package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;
import org.tornotron.echno_backend.billing.gateway.MandateMethod;

@Schema(description = "The e-mandate terms a recurring checkout registers, shown to the buyer before the provider widget opens.")
@Value
@Builder
public class CheckoutMandateTermsDto {

    @Schema(description = "Ceiling the mandate is registered for, in paise: the cycle amount.", example = "999900")
    long amountCapPaise;

    @Schema(description = "Hours of notice before each debit.", example = "24")
    int preDebitNoticeHours;

    @Schema(description = "The instrument the mandate is registered on; UNKNOWN until the buyer picks one in the provider widget.", example = "UPI_AUTOPAY")
    MandateMethod method;

    @Schema(description = "The cycle is above the RBI cap, so each charge needs the buyer's authentication.", example = "false")
    boolean perChargeApproval;
}
