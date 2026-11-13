package com.shortly.url;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator();

    @Test
    void codesAreFixedLengthBase62() {
        for (long id : new long[] {0, 1, 61, 62, 1_000_000, ShortCodeGenerator.SPACE - 1}) {
            assertThat(generator.encode(id)).hasSize(ShortCodeGenerator.LENGTH).matches("[0-9A-Za-z]+");
        }
    }

    @Test
    void distinctIdsNeverCollide() {
        Set<String> codes = new HashSet<>();
        for (long id = 0; id < 200_000; id++) {
            codes.add(generator.encode(id));
        }
        // Also probe the top of the range, where an overflow bug in the permutation would show up.
        for (long id = ShortCodeGenerator.SPACE - 200_000; id < ShortCodeGenerator.SPACE; id++) {
            codes.add(generator.encode(id));
        }
        assertThat(codes).hasSize(400_000);
    }

    @Test
    void consecutiveIdsDoNotProduceAdjacentCodes() {
        String a = generator.encode(1000);
        String b = generator.encode(1001);
        // Plain Base62 of a counter would differ only in the last character.
        assertThat(a.substring(0, 4)).isNotEqualTo(b.substring(0, 4));
    }

    @Test
    void isDeterministic() {
        assertThat(generator.encode(42)).isEqualTo(generator.encode(42));
    }

    @Test
    void rejectsNegativeIds() {
        assertThatThrownBy(() -> generator.encode(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
