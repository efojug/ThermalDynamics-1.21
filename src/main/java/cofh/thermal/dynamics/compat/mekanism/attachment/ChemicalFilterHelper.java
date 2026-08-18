package cofh.thermal.dynamics.compat.mekanism.attachment;

import mekanism.api.chemical.ChemicalStack;

final class ChemicalFilterHelper {

    private ChemicalFilterHelper() {

    }

    static boolean valid(ChemicalFilter filter, ChemicalStack chemical) {

        return filter.valid(chemical);
    }

}
