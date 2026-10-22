package com.mergetacticsai
import com.mergetacticsai.data.CatalogItem
import com.mergetacticsai.data.KnownCards
import com.mergetacticsai.data.Troop
import com.mergetacticsai.strategy.*
import org.junit.Assert.*
import org.junit.Test
class PolicyTest {
    private fun t(id: String, trait: String, cost: Int = 2, legacy: Boolean = false) = Troop(id, id, cost, trait, legacy, "", "")
    private val knight = t("knight", "noble"); private val ghost = t("royal_ghost", "undead")
    private val archer = t("archer", "clan"); private val prince = t("prince", "noble", 5)
    private val all = setOf("knight", "royal_ghost", "archer", "prince")
    private val traits = listOf(CatalogItem("noble", "Благородный", "", true, listOf(2, 4, 6)), CatalogItem("boss", "Босс", "", true, listOf(1, 2, 3, 4)))

    @Test fun strictBlocksOtherTraits() {
        val p = TroopPolicy(all, setOf("noble", "undead"), true)
        assertTrue(p.allows(knight, false)); assertTrue(p.allows(ghost, false)); assertFalse(p.allows(archer, false))
    }
    @Test fun strictKeepsMergePartner() = assertTrue(TroopPolicy(all, setOf("noble"), true).allows(archer, true))
    @Test fun strategicBlocksNothing() = assertTrue(TroopPolicy(all, setOf("noble"), false).allows(archer, false))
    @Test fun strictUnknownBlocked() = assertFalse(TroopPolicy(all, setOf("noble"), true).allows(null, false))
    @Test fun strictRespectsDisabledTroop() = assertFalse(TroopPolicy(setOf("prince"), emptySet(), true).allows(knight, false))
    @Test fun bonusPrefersSelectedTraitAndThreshold() {
        val p = TroopPolicy(all, setOf("noble"), false)
        val first = p.bonus(knight, emptyMap()); val second = p.bonus(prince, mapOf("noble" to 1))
        assertTrue(second > first); assertTrue(first > p.bonus(archer, emptyMap()))
    }
    @Test fun legacyPenalised() = assertTrue(TroopPolicy(setOf("x"), emptySet(), false).bonus(t("x", "clan", 4, true), emptyMap()) < 0)

    @Test fun progressShowsNextThreshold() {
        val troops = listOf(knight, prince).associateBy { it.id }
        val p = TraitEngine.progress(setOf("knight", "prince"), troops, traits).first { it.traitId == "noble" }
        assertEquals(2, p.count); assertEquals(2, p.activeTier); assertEquals(4, p.next); assertEquals(2, p.missing)
    }
    @Test fun knownCardsLookupAndSerialize() {
        val k = KnownCards(); k.add(0x123456789ABCDEFL, "knight")
        assertEquals("knight", k.lookup(0x123456789ABCDEFL xor 3L)); assertNull(k.lookup(0x123456789ABCDEFL.inv()))
        assertEquals("knight", KnownCards.parse(k.serialize()).lookup(0x123456789ABCDEFL))
    }
}
