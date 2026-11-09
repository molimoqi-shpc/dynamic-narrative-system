package com.bs.narrative;

import com.bs.narrative.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@AutoConfigureMockMvc @Transactional
class AuthAccessIT {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper mapper=new ObjectMapper();
    private String token() throws Exception {
        var result=mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"player_demo\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.playerId").value("p_demo")).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }
    @BeforeEach void otherAccount() {
        jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id) VALUES('p_other','player_01','other_user','unused','scene_fengdu')");
        jdbc.update("INSERT INTO game_session(session_id,player_id,status) VALUES('sess_other','p_other','PLAYING')");
    }
    @Test void wrongPasswordAndFakeOrMissingTokensAreRejected() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"player_demo\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/game/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/game/sessions").header("Authorization","Bearer demo-placeholder-token"))
                .andExpect(status().isUnauthorized());
    }
    @Test void loginMeAndLogoutInvalidateTheSameToken() throws Exception {
        String token=token();
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("player_demo"));
        mvc.perform(post("/api/auth/logout").header("Authorization","Bearer "+token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token))
                .andExpect(status().isUnauthorized());
    }
    @Test void mainModelSettingsAreOwnedPersistentAndAllowOnlyFlashOrPro()throws Exception{
        String header="Bearer "+token();
        int beforeLogs=jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log WHERE session_id='sess_demo_001'",Integer.class);
        mvc.perform(get("/api/game/main-model").param("sessionId","sess_other").header("Authorization",header)).andExpect(status().isForbidden());
        mvc.perform(post("/api/game/main-model").header("Authorization",header).contentType("application/json").content("{\"sessionId\":\"sess_other\",\"model\":\"deepseek-v4-pro\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/game/main-model").header("Authorization",header).contentType("application/json").content("{\"sessionId\":\"sess_demo_001\",\"model\":\"other\"}")).andExpect(status().isBadRequest());
        for(String model:List.of("deepseek-v4-pro","deepseek-v4-flash")){
            mvc.perform(post("/api/game/main-model").header("Authorization",header).contentType("application/json").content("{\"sessionId\":\"sess_demo_001\",\"model\":\""+model+"\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.model").value(model));
            mvc.perform(get("/api/game/main-model").param("sessionId","sess_demo_001").header("Authorization",header)).andExpect(status().isOk()).andExpect(jsonPath("$.model").value(model)).andExpect(jsonPath("$.options.length()").value(2)).andExpect(jsonPath("$.apiKey").doesNotExist());
        }
        assertEquals(beforeLogs,jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log WHERE session_id='sess_demo_001'",Integer.class));
    }
    @Test void livingPlayersDeathSummaryRequestIsAConflictInsteadOfAnInternalError()throws Exception{
        String header="Bearer "+token();
        jdbc.update("UPDATE game_session SET status='PLAYING' WHERE session_id='sess_demo_001'");
        mvc.perform(post("/api/game/death").header("Authorization",header).contentType("application/json").content("{\"sessionId\":\"sess_demo_001\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(400302));
        assertEquals("PLAYING",jdbc.queryForObject("SELECT status FROM game_session WHERE session_id='sess_demo_001'",String.class));
    }
    @Test void authenticatedIdentityIsInjectedButForgedIdentityIsForbidden() throws Exception {
        String token=token();
        mvc.perform(get("/api/game/sessions").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.playerId").value("p_demo"));
        mvc.perform(get("/api/game/sessions").param("playerId","p_other").header("Authorization","Bearer "+token))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/game/start").header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"playerId\":\"p_other\"}"))
                .andExpect(status().isForbidden());
    }
    @Test void anotherSessionsReadsAndRewindsAreForbiddenBeforeMutation() throws Exception {
        String token=token();
        mvc.perform(get("/api/game/session-status").param("sessionId","sess_other").header("Authorization","Bearer "+token))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/game/load-to-round").header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"sessionId\":\"sess_other\",\"targetRound\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/game/restart").header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"prevSessionId\":\"sess_other\",\"inheritWorld\":true}"))
                .andExpect(status().isForbidden());
        assertEquals("PLAYING",jdbc.queryForObject("SELECT status FROM game_session WHERE session_id='sess_other'",String.class));
    }
    @Test void tamperedExpiredAndRevokedTokensAreRejected() throws Exception {
        String token=token();
        String tampered=token.substring(0,token.length()-8)+"AAAAAAAA";
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+tampered)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE auth_token SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE player_id='p_demo'");
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void missingSessionMalformedBodyAndOversizedBodyAreControlledClientErrors() throws Exception {
        String header="Bearer "+token();
        mvc.perform(get("/api/game/scene").header("Authorization",header)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/game/start").header("Authorization",header).contentType("application/json").content("not json"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/game/start").header("Authorization",header).contentType("application/json")
                .content("{\"x\":\""+"a".repeat(17000)+"\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void invalidGameplayFieldsAndClientSuppliedIntentCannotMutateState() throws Exception {
        String header="Bearer "+token();
        int checkpoints=jdbc.queryForObject("SELECT COUNT(*) FROM session_checkpoint",Integer.class);
        for (String body : List.of(
                "{\"sessionId\":\"sess_demo_001\"}",
                "{\"sessionId\":\"sess_demo_001\",\"targetRound\":-1}",
                "{\"sessionId\":\"sess_demo_001\",\"targetRound\":\"bad\"}")) {
            mvc.perform(post("/api/game/load-to-round").header("Authorization",header)
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/game/action").param("sessionId","sess_demo_001")
                .header("Authorization",header).contentType("application/json")
                .content("{\"sceneId\":\"scene_fengdu\",\"playerText\":\"行动\",\"structuredIntent\":{\"intent\":\"ATTACK\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/game/action").param("sessionId","sess_demo_001")
                .header("Authorization",header).contentType("application/json")
                .content("{\"sceneId\":\"scene_fengdu\",\"playerText\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/game/start").header("Authorization",header).contentType("application/json")
                .content("{\"viewMode\":\"invalid\"}")).andExpect(status().isBadRequest());
        assertEquals(checkpoints,jdbc.queryForObject("SELECT COUNT(*) FROM session_checkpoint",Integer.class));
    }
}
