package com.qwe7002.telegram_rc;

class polling_json {
    long offset;
    int timeout;
    @SuppressWarnings("unused")
    final String[] allowed_updates = {"message", "channel_post"};
}
