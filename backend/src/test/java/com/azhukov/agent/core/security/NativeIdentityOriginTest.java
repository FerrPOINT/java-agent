package com.azhukov.agent.core.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NativeIdentityOriginTest {
    @AfterEach void cleanup(){NativeIdentityOrigin.clear();}
    @Test void native_user_key_cannot_claim_a_different_native_storage_scope() {
        var origin=new NativeIdentityOrigin.Origin(NativeIdentityOrigin.Kind.USER_API_KEY,"native-user",false);
        assertThat(origin.permits("native-user")).isTrue();assertThat(origin.permits("other-user")).isFalse();
        assertThat(origin.permits(null)).isFalse();assertThat(origin.permits(" ")).isFalse();
    }
    @Test void actual_gateway_authority_keeps_native_namespace_without_a_fake_central_subject() {
        var origin=new NativeIdentityOrigin.Origin(NativeIdentityOrigin.Kind.GATEWAY_KEY,"user-1",true);
        NativeIdentityOrigin.bind(origin);assertThat(NativeIdentityOrigin.current()).isEqualTo(origin);
        assertThat(origin.permits("telegram-sender-native-scope")).isTrue();
        NativeIdentityOrigin.clear();assertThat(NativeIdentityOrigin.current()).isNull();
    }
}
