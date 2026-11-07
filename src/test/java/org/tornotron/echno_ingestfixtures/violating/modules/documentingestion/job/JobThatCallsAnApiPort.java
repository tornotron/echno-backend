package org.tornotron.echno_ingestfixtures.violating.modules.documentingestion.job;

import org.tornotron.echno_ingestfixtures.violating.modules.workprogress.api.BoqPort;

public class JobThatCallsAnApiPort {
    public void run(BoqPort boq) {
        boq.addItem("1.1");
    }
}
