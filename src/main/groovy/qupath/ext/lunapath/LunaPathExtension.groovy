package qupath.ext.lunapath

import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.extensions.QuPathExtension

class LunaPathExtension implements QuPathExtension {

    String name = "LunaPath"
    String description = "Cell detection, class sync, and phenotype classification"

    @Override
    void installExtension(QuPathGUI qupath) {
        qupath.installCommand("LunaPath>Add Detections from GeoJSON", () -> {
            new AddDetectionsCommand(qupath).run()
        })
        qupath.installCommand("LunaPath>Sync Classes & Export", () -> {
            new SyncClassesCommand(qupath).run()
        })
        qupath.installCommand("LunaPath>Phenotype Classifier", () -> {
            new PhenotypeClassifierCommand(qupath).run()
        })
    }
}