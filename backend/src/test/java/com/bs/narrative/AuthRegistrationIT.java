package com.bs.narrative;
import com.bs.narrative.service.AuthService;
import com.bs.narrative.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@AutoConfigureMockMvc @Transactional
class AuthRegistrationIT {
 @Autowired AuthService auth;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
 String name(){return "qa_entry_"+UUID.randomUUID().toString().substring(0,8);}
 @Test void createsIndependentAccountAndLoginWithoutSharingPlayerIdentity(){String user=name();assertEquals(true,auth.register(user,"ValidPass_123").get("registered"));var row=jdbc.queryForMap("SELECT * FROM player_profile WHERE username=?",user);assertTrue(row.get("password_hash").toString().startsWith("$2"));assertNotEquals("ValidPass_123",row.get("password_hash"));assertNull(row.get("active_session_id"));var login=auth.login(user,"ValidPass_123");assertEquals(row.get("player_id"),auth.authenticate(login.get("token").toString()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE god_id=? AND session_id IS NULL",Integer.class,row.get("god_id")));}
 @Test void rejectsDuplicateAndInvalidCredentials(){String user=name();auth.register(user,"ValidPass_123");assertThrows(BusinessException.class,()->auth.register(user,"AnotherPass_123"));assertThrows(BusinessException.class,()->auth.login(user,"wrong-password"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM player_profile WHERE username=?",Integer.class,user));}
 @Test void enforcesUsernameAndUtf8PasswordBounds(){assertThrows(BusinessException.class,()->auth.register("短","ValidPass_123"));assertThrows(BusinessException.class,()->auth.register(name(),"short"));assertThrows(BusinessException.class,()->auth.register(name(),"神".repeat(25)));}
 @Test void registrationIsPublicAndReturnsNoTokenOrSecret() throws Exception {String user=name();mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\""+user+"\",\"password\":\"ValidPass_123\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.registered").value(true)).andExpect(jsonPath("$.token").doesNotExist()).andExpect(jsonPath("$.password").doesNotExist());}
}
