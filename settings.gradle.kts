rootProject.name = "arpeon"

include("apps:desktop")
include("modules:arpeon-domain", "modules:arpeon-musicxml")
include("modules:arpeon-audio", "modules:arpeon-dsp", "modules:arpeon-tuner")
include("modules:arpeon-transport", "modules:arpeon-playback")
include("modules:arpeon-import")
include("modules:arpeon-omr")
include("modules:arpeon-analysis")
include("modules:arpeon-ml")
