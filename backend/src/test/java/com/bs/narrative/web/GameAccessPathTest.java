package com.bs.narrative.web;

import com.bs.narrative.exception.*;
import com.bs.narrative.service.AuthService;
import java.net.URI;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.pattern.PathPatternParser;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercise MVC route matching and the filter together; no database or model. */
class GameAccessPathTest {
 private AuthService auth;
 private MockMvc mvc;
 @RestController static class Probe {
  @GetMapping("/api/game/sessions") public String list(){return "private accounts";}
  @GetMapping("/api/game/session-status") public String read(){return "private save";}
  @PostMapping("/api/engine/demo/pass2-test") public String model(){return "model endpoint reached";}
  @PostMapping("/api/auth/login") public String login(){return "public login endpoint";}
 }
 @BeforeEach void setup(){
  auth=mock(AuthService.class);
  mvc=MockMvcBuilders.standaloneSetup(new Probe()).setPatternParser(new PathPatternParser()).addFilters(new GameAccessFilter(auth,mock(com.bs.narrative.service.MainModelService.class))).build();
 }
 @ParameterizedTest @ValueSource(strings={
  "/api/game/sessions","/api;matrix/game/sessions","/%61pi/game/sessions","/api/%67ame/sessions","/api/game/sessions;matrix=1"
 })
 void matchingPrivateRoutesAlwaysRequireAuthentication(String path)throws Exception{
  when(auth.authenticate(nullable(String.class))).thenThrow(new BusinessException(ErrorCode.AUTH_REQUIRED,"please log in"));
  mvc.perform(get(URI.create(path))).andExpect(status().isUnauthorized());
  verify(auth).authenticate(null);
 }
 @ParameterizedTest @ValueSource(strings={
  "/api/game/session-status","/api;matrix/game/session-status","/api/game;matrix/session-status","/%61pi/game/session-status","/api/%67ame/session-status","/api/game/session-status;matrix=1"
 })
 void matchingGameRoutesAlwaysCheckSaveOwnership(String path)throws Exception{
  when(auth.authenticate("valid")).thenReturn("owner");
  doThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN,"foreign save")).when(auth).requireOwnedSession("foreign","owner");
  mvc.perform(get(URI.create(path)).param("sessionId","foreign").header("Authorization","Bearer valid")).andExpect(status().isForbidden());
  verify(auth).requireOwnedSession("foreign","owner");
 }
 @ParameterizedTest @ValueSource(strings={"/api/engine/demo/pass2-test","/api;matrix/engine/demo/pass2-test","/%61pi/engine/demo/pass2-test"})
 void paidModelRoutesCannotBeReachedWithoutALogin(String path)throws Exception{
  when(auth.authenticate(nullable(String.class))).thenThrow(new BusinessException(ErrorCode.AUTH_REQUIRED,"please log in"));
  mvc.perform(post(URI.create(path))).andExpect(status().isUnauthorized());
 }
 @Test void appliesAuthenticationInsideAContextPath()throws Exception{
  when(auth.authenticate(nullable(String.class))).thenThrow(new BusinessException(ErrorCode.AUTH_REQUIRED,"please log in"));
  mvc.perform(get(URI.create("/demo/api;matrix/game/sessions")).contextPath("/demo")).andExpect(status().isUnauthorized());
 }
 @ParameterizedTest @ValueSource(strings={"/api/auth/login","/api;matrix/auth/login"})
 void legitimateLoginEndpointRemainsPublic(String path)throws Exception{
  mvc.perform(post(URI.create(path))).andExpect(status().isOk());
  verifyNoInteractions(auth);
 }
}
