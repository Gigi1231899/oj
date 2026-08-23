package com.decade.doj.problem;

import com.decade.doj.problem.service.impl.ProblemServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProblemImportServiceTest {

    @Test
    void shouldNormalizeProblemSourceType() {
        assertEquals("official", ProblemServiceImpl.normalizeProblemSourceType("官方题"));
        assertEquals("official", ProblemServiceImpl.normalizeProblemSourceType("official"));
        assertEquals("personal", ProblemServiceImpl.normalizeProblemSourceType("个人上传"));
        assertEquals("personal", ProblemServiceImpl.normalizeProblemSourceType("personal"));
    }
}
