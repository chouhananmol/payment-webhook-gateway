package dev.anmol.webhooks;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The /admin operational endpoints must not be reachable without the API key.
 */
@SpringBootTest(properties = "webhooks.admin.api-key=test-admin-key")
@AutoConfigureMockMvc
class AdminSecurityTest {

    private static final String HEADER = "X-Admin-Api-Key";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void deadLetterQueue_withoutApiKey_isRejectedWith401() throws Exception {
        mockMvc.perform(get("/admin/webhooks/dead-letter"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deadLetterQueue_withWrongApiKey_isRejectedWith401() throws Exception {
        mockMvc.perform(get("/admin/webhooks/dead-letter").header(HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deadLetterQueue_withCorrectApiKey_isAllowed() throws Exception {
        mockMvc.perform(get("/admin/webhooks/dead-letter").header(HEADER, "test-admin-key"))
                .andExpect(status().isOk());
    }
}
