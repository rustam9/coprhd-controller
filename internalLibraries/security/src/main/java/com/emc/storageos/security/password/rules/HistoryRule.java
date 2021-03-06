/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.security.password.rules;

import com.emc.storageos.security.password.Password;
import com.emc.storageos.security.password.PasswordUtils;
import com.emc.storageos.svcs.errorhandling.resources.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.text.MessageFormat;
import java.util.List;

public class HistoryRule implements Rule {
    private static final Logger _log = LoggerFactory.getLogger(HistoryRule.class);

    private int historySize = 5;
    private PasswordUtils passwordUtils;

    public HistoryRule(int size, PasswordUtils passwordUtils) {
        this.historySize = size;
        this.passwordUtils = passwordUtils;
    }

    /**
     * validate the new password is not in history.
     * 
     * @param password
     */
    @Override
    public void validate(Password password) {
        if (historySize == 0) {
            return;
        }

        String username = password.getUsername();
        if (username == null || username.trim().length() == 0) {
            return;
        }

        String text = password.getPassword();
        List<String> previousPasswords = password.getPreviousPasswords(historySize);
        if (previousPasswords.isEmpty()) {
            _log.info("Pass since no password in history list.");
            return;
        }

        for (int i = 0; i < previousPasswords.size(); i++) {
            if (passwordUtils.match(text, previousPasswords.get(i))) {
                _log.info(MessageFormat.format("fail, match previous password #{0}", i));
                throw BadRequestException.badRequests.passwordInvalidHistory(historySize);
            }
            _log.info(MessageFormat.format("good, do not match previous password #{0}", i));
        }

        _log.info("pass");
    }
}
