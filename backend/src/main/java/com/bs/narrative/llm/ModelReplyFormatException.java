package com.bs.narrative.llm;

/** A received reply has been saved; only its protocol fields still need repair. */
public final class ModelReplyFormatException extends LLMApiException {
    private final ModelProtocol.Stage stage;

    public ModelReplyFormatException(ModelProtocol.Stage stage, String message, String raw, Throwable cause) {
        super(message, false, cause);
        this.stage = java.util.Objects.requireNonNull(stage);
        withPartialResponse(raw, "FORMAT");
    }

    public ModelProtocol.Stage stage() { return stage; }
}
