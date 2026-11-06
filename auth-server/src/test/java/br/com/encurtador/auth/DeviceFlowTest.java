package br.com.encurtador.auth;

import br.com.encurtador.auth.support.AuthServerTest;
import br.com.encurtador.auth.support.OAuthFlows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fluxo de dispositivo da CLI: cliente público, confirmação de cada código e token (ADR-0003, D3, D4 e D9). */
@AuthServerTest
class DeviceFlowTest {

    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final Pattern INPUT = Pattern.compile("<input[^>]*>");
    private static final Pattern NAME = Pattern.compile("name=\"([^\"]+)\"");
    private static final Pattern VALUE = Pattern.compile("value=\"([^\"]*)\"");

    @Autowired
    MockMvc mvc;

    @Autowired
    OAuthFlows flows;

    @Test
    void cliGetsATokenOnlyAfterTheUserConfirmsTheCode() throws Exception {
        JsonNode device = deviceAuthorization();
        String deviceCode = device.get("device_code").asString();
        String userCode = device.get("user_code").asString();
        assertThat(device.get("verification_uri").asString()).isEqualTo("http://localhost:8090/activate");

        token(deviceCode).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("authorization_pending"));

        String email = flows.newAccount();
        MockHttpSession session = flows.login(email, OAuthFlows.PASSWORD).session();
        mvc.perform(get("/activate").session(session).param("user_code", userCode))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(userCode)));
        confirm(session, userCode);

        MvcResult result = token(deviceCode).andExpect(status().isOk()).andReturn();
        Jwt access = flows.decode(flows.tokens(result).accessToken());
        assertThat(access.getClaimAsString("email")).isEqualTo(email);
        assertThat(access.getAudience()).containsExactly("encurtador-api");
        assertThat(flows.tokens(result).refreshToken()).isNull();
    }

    @Test
    void everyCodeNeedsItsOwnConfirmation() throws Exception {
        MockHttpSession session = flows.login(flows.newAccount(), OAuthFlows.PASSWORD).session();
        confirm(session, deviceAuthorization().get("user_code").asString());

        // Um segundo código, de outra pessoa, não é aprovado só por chegar com a sessão aberta.
        String otherCode = deviceAuthorization().get("user_code").asString();
        mvc.perform(post("/oauth2/device_verification").session(session).with(csrf()).param("user_code", otherCode))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("consent")));
    }

    @Test
    void unknownClientIsRejected() throws Exception {
        mvc.perform(post("/oauth2/device_authorization").param("client_id", "desconhecido"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void confidentialClientCannotUseTheDeviceFlowWithoutItsSecret() throws Exception {
        mvc.perform(post("/oauth2/device_authorization").param("client_id", OAuthFlows.WEB_CLIENT))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode deviceAuthorization() throws Exception {
        MvcResult result = mvc.perform(post("/oauth2/device_authorization").param("client_id", "encurtador-cli")
                        .param("scope", "email"))
                .andExpect(status().isOk())
                .andReturn();
        return flows.body(result);
    }

    private org.springframework.test.web.servlet.ResultActions token(String deviceCode) throws Exception {
        return mvc.perform(post("/oauth2/token").param("grant_type", DEVICE_GRANT).param("device_code", deviceCode)
                .param("client_id", "encurtador-cli"));
    }

    /** Digita o código e confirma na página de consentimento, como o usuário faria. */
    private void confirm(MockHttpSession session, String userCode) throws Exception {
        String consentPage = mvc.perform(post("/oauth2/device_verification").session(session).with(csrf())
                        .param("user_code", userCode))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var consent = post("/oauth2/device_verification").session(session).with(csrf());
        Matcher input = INPUT.matcher(consentPage);
        boolean hasState = false;
        while (input.find()) {
            Matcher name = NAME.matcher(input.group());
            Matcher value = VALUE.matcher(input.group());
            if (name.find() && value.find()) {
                consent.param(name.group(1), value.group(1));
                hasState |= name.group(1).equals("state");
            }
        }
        assertThat(hasState).as("state na página de consentimento:%n%s", consentPage).isTrue();
        mvc.perform(consent).andExpect(status().is3xxRedirection());
    }
}
