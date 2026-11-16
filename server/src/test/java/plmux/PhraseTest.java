package plmux;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhraseTest {

    private static Model.Waveform waveform(int[] beatTimes, List<Model.Phrase> phrases) {
        return new Model.Waveform("k", "RGB", 10_000, null, null, beatTimes, new int[beatTimes.length], List.of(), phrases);
    }

    @Test
    void findsTheBeatAtATime() {
        Model.Waveform w = waveform(new int[]{100, 600, 1100, 1600}, List.of());
        assertEquals(0, w.beatAt(0));
        assertEquals(0, w.beatAt(99.9));
        assertEquals(1, w.beatAt(100));
        assertEquals(2, w.beatAt(1099));
        assertEquals(4, w.beatAt(1600));
        assertEquals(4, w.beatAt(60_000));
        assertEquals(0, waveform(new int[0], List.of()).beatAt(500));
    }

    @Test
    void findsThePhraseAtABeat() {
        Model.Phrase intro = new Model.Phrase(1, 33, "intro", "Intro 1", "HIGH", "#c80000");
        Model.Phrase up = new Model.Phrase(33, 65, "up", "Up 1", "HIGH", "#8c32ff");
        Model.Waveform w = waveform(new int[0], List.of(intro, up));
        assertNull(w.phraseAt(0));
        assertSame(intro, w.phraseAt(1));
        assertSame(intro, w.phraseAt(32));
        assertSame(up, w.phraseAt(33));
        assertNull(w.phraseAt(65));
    }

    @Test
    void derivesKindFromLabel() {
        assertEquals("verse", WaveformEncoder.phraseKind("Verse 2"));
        assertEquals("up", WaveformEncoder.phraseKind("Up 1"));
        assertEquals("chorus", WaveformEncoder.phraseKind("Chorus"));
        assertEquals("down", WaveformEncoder.phraseKind("Down"));
        assertNull(WaveformEncoder.phraseKind("Unknown High"));
        assertNull(WaveformEncoder.phraseKind(null));
        assertTrue(WaveformEncoder.encodePhrases(null).isEmpty());
    }

    @Test
    void demoTracksHavePhrasesCoveringTheWholeTrack() {
        DemoSource demo = new DemoSource();
        Model.Waveform w = demo.waveform(1);
        assertNotNull(w);
        List<Model.Phrase> phrases = w.phrases();
        assertEquals("intro", phrases.getFirst().kind());
        assertEquals(1, phrases.getFirst().beat());
        assertEquals("outro", phrases.getLast().kind());
        assertEquals(w.beatTimesMs().length + 1, phrases.getLast().endBeat());
        for (int i = 1; i < phrases.size(); i++) {
            assertEquals(phrases.get(i - 1).endBeat(), phrases.get(i).beat());
        }
        assertEquals("chorus", w.phraseAt(w.beatAt(32 * 4 * 500)).kind());
    }
}
