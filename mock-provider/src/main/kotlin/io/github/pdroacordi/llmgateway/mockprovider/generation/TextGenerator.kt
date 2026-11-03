package io.github.pdroacordi.llmgateway.mockprovider.generation

import java.util.SplittableRandom

/** `lorem` content: words drawn from a fixed vocabulary by a generator seeded from the conversation. */
class TextGenerator(
    private val vocabulary: List<String> = VOCABULARY,
) {
    fun lorem(
        seed: Long,
        tokens: Int,
    ): String {
        val random = SplittableRandom(seed)
        return List(tokens) { vocabulary[random.nextInt(vocabulary.size)] }.joinToString(" ")
    }

    companion object {
        // Written for this project; each word is one token. Tests must not assert exact words (FND-03 section 5).
        val VOCABULARY: List<String> =
            listOf(
                "amber",
                "anchor",
                "apple",
                "arrow",
                "autumn",
                "basket",
                "beacon",
                "bridge",
                "button",
                "canyon",
                "carpet",
                "cedar",
                "chalk",
                "cloud",
                "copper",
                "cotton",
                "crystal",
                "desert",
                "dragon",
                "eagle",
                "ember",
                "engine",
                "fabric",
                "falcon",
                "feather",
                "forest",
                "garden",
                "glacier",
                "granite",
                "harbor",
                "hollow",
                "island",
                "jacket",
                "kettle",
                "ladder",
                "lantern",
                "meadow",
                "mirror",
                "morning",
                "needle",
                "orchard",
                "paper",
                "pebble",
                "pepper",
                "planet",
                "pocket",
                "puzzle",
                "quiet",
                "rabbit",
                "ribbon",
                "river",
                "saddle",
                "silver",
                "stone",
                "summer",
                "thunder",
                "timber",
                "tunnel",
                "valley",
                "velvet",
                "willow",
                "window",
                "winter",
                "yellow",
            )
    }
}
