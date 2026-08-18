package com.assessment.shortener.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeGeneratorTest {

    @Test
    void generatesCodesOfConfiguredLength() {
        CodeGenerator gen = new CodeGenerator(7);
        assertEquals(7, gen.next().length());
    }

    @Test
    void codesAreBase62() {
        CodeGenerator gen = new CodeGenerator(8);
        for (int i = 0; i < 100; i++) {
            assertTrue(gen.next().matches("[A-Za-z0-9]{8}"));
        }
    }

    @Test
    void codesAreReasonablyUnique() {
        CodeGenerator gen = new CodeGenerator(8);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(gen.next());
        }
        assertEquals(10_000, seen.size());
    }

    @Test
    void rejectsInvalidLengths() {
        assertThrows(IllegalArgumentException.class, () -> new CodeGenerator(3));
        assertThrows(IllegalArgumentException.class, () -> new CodeGenerator(33));
    }

    @Test
    void validatesCustomCodes() {
        assertTrue(CodeGenerator.isValidCustomCode("my-link_1"));
        assertFalse(CodeGenerator.isValidCustomCode("ab"));            // too short
        assertFalse(CodeGenerator.isValidCustomCode("has space"));
        assertFalse(CodeGenerator.isValidCustomCode("emoji❤"));
        assertFalse(CodeGenerator.isValidCustomCode(null));
    }
}
