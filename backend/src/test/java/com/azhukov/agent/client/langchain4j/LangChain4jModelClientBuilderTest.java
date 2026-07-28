package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.Message;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jModelClientBuilderTest {

    @Test
    void builderConstructorCreatesBean() {
        AgentProperties props = new AgentProperties();
        props.getModel().setProvider("openai-compatible");
        props.getModel().setBaseUrl("https://api.example.com/v1");
        props.getModel().setApiKey("sk-test");
        props.getModel().setModelName("gpt-4o");
        props.getModel().setTimeoutSeconds(10);
        props.getModel().setMaxRetries(2);
        props.getModel().setTemperature(0.5);

        ModelClient client = new LangChain4jModelClient(props, usage -> {}, null, null);
        assertThat(client).isNotNull();
    }
}
