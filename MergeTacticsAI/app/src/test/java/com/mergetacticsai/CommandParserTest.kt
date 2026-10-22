package com.mergetacticsai
import com.mergetacticsai.voice.*
import org.junit.Assert.assertEquals
import org.junit.Test
class CommandParserTest {
    @Test fun play() = assertEquals(Command.PLAY, CommandParser.parse("Играй в Merge Tactics"))
    @Test fun stop() = assertEquals(Command.STOP, CommandParser.parse("Остановись"))
    @Test fun noMerge() = assertEquals(Command.NO_UNNECESSARY_MERGE, CommandParser.parse("Не объединяй юнитов без необходимости"))
    @Test fun electric() = assertEquals(Command.ELECTRIC, CommandParser.parse("Играй через электрическую стратегию"))
    @Test fun openGame() = assertEquals(Command.OPEN_GAME, CommandParser.parse("Открой Clash Royale"))
    @Test fun unknown() = assertEquals(Command.UNKNOWN, CommandParser.parse("привет"))
    @Test fun modeMerge() = assertEquals(Command.MODE_MERGE, CommandParser.parse("Включи режим объединения"))
    @Test fun modeCustom() = assertEquals(Command.MODE_CUSTOM, CommandParser.parse("Включи спец сборку"))
    @Test fun setBuild() {
        val t = "Собери из гоблина, лучницы и рыцаря"
        assertEquals(Command.SET_BUILD, CommandParser.parse(t))
        assertEquals(listOf("гоблина", "лучницы", "рыцаря"), CommandParser.extractUnits(t))
    }
}
