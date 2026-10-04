package com.github.guoyyit.webpushservicejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.webpushservicejava.config.PushProperties;
import com.github.guoyyit.webpushservicejava.crypto.EcKeyUtils;
import com.github.guoyyit.webpushservicejava.domain.ErrorCode;
import com.github.guoyyit.webpushservicejava.domain.Subscription;
import com.github.guoyyit.webpushservicejava.persistence.PersistenceService;
import com.github.guoyyit.webpushservicejava.subscription.SubscriptionService;
import com.github.guoyyit.webpushservicejava.web.ServiceException;
import com.github.guoyyit.webpushservicejava.web.dto.SubscriptionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionServiceTest {

    private SubscriptionService subscriptionService;
    private PersistenceService persistenceService;
    private PushProperties properties;
    private String validPublicKey;
    private String validAuth;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        properties = new PushProperties();
        properties.setDataDir(tempDir.toString());
        persistenceService = new PersistenceService(properties, new ObjectMapper());
        ObjectProvider<com.github.guoyyit.webpushservicejava.message.MessageService> emptyProvider =
                new ObjectProvider<>() {
                    @Override
                    public com.github.guoyyit.webpushservicejava.message.MessageService getObject() {
                        throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(
                                com.github.guoyyit.webpushservicejava.message.MessageService.class);
                    }
                };
        subscriptionService = new SubscriptionService(persistenceService, emptyProvider);

        KeyPair keyPair = EcKeyUtils.generateP256KeyPair();
        validPublicKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(EcKeyUtils.encodeUncompressedPublic(keyPair.getPublic()));
        validAuth = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    }

    @Test
    void createGetUpdateDeleteLifecycle() {
        Subscription created = subscriptionService.create(validRequest());
        assertNotNull(created.getId());

        Subscription fetched = subscriptionService.require(created.getId());
        assertEquals(created.getEndpoint(), fetched.getEndpoint());

        SubscriptionRequest updated = validRequest();
        updated.setEndpoint("http://localhost:8080/push/updated-id");
        subscriptionService.update(created.getId(), updated);
        assertEquals("http://localhost:8080/push/updated-id",
                subscriptionService.require(created.getId()).getEndpoint());

        assertTrue(subscriptionService.delete(created.getId()));
        assertFalse(subscriptionService.delete(created.getId()));
        assertThrows(ServiceException.class, () -> subscriptionService.require(created.getId()));
    }

    @Test
    void rejectsInvalidEndpointScheme() {
        SubscriptionRequest request = validRequest();
        request.setEndpoint("ftp://localhost/push/abc");
        ServiceException exception = assertThrows(ServiceException.class,
                () -> subscriptionService.create(request));
        assertEquals(ErrorCode.INVALID_ENDPOINT, exception.getErrorCode());
    }

    @Test
    void rejectsEndpointWithoutRegisteredPath() {
        SubscriptionRequest request = validRequest();
        request.setEndpoint("http://localhost:8080/somewhere/else");
        ServiceException exception = assertThrows(ServiceException.class,
                () -> subscriptionService.create(request));
        assertEquals(ErrorCode.INVALID_ENDPOINT, exception.getErrorCode());
    }

    @Test
    void rejectsMalformedPublicKey() {
        SubscriptionRequest request = validRequest();
        request.setP256dh("not-a-key");
        ServiceException exception = assertThrows(ServiceException.class,
                () -> subscriptionService.create(request));
        assertEquals(ErrorCode.INVALID_PUBLIC_KEY, exception.getErrorCode());
    }

    @Test
    void rejectsWrongLengthAuthSecret() {
        SubscriptionRequest request = validRequest();
        request.setAuth(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[8]));
        ServiceException exception = assertThrows(ServiceException.class,
                () -> subscriptionService.create(request));
        assertEquals(ErrorCode.INVALID_ARGUMENT, exception.getErrorCode());
    }

    @Test
    void subscriptionsSurviveRestart() {
        Subscription created = subscriptionService.create(validRequest());

        SubscriptionService restarted = new SubscriptionService(
                new PersistenceService(properties, new ObjectMapper()),
                new ObjectProvider<>() {
                    @Override
                    public com.github.guoyyit.webpushservicejava.message.MessageService getObject() {
                        throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(
                                com.github.guoyyit.webpushservicejava.message.MessageService.class);
                    }
                });

        assertEquals(created.getEndpoint(), restarted.require(created.getId()).getEndpoint());
    }

    private SubscriptionRequest validRequest() {
        SubscriptionRequest request = new SubscriptionRequest();
        request.setEndpoint("http://localhost:8080/push/endpoint-1");
        request.setP256dh(validPublicKey);
        request.setAuth(validAuth);
        return request;
    }
}
