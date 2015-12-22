package org.motechproject.nms.ldapbrowser.ldap.web.actions.get;

import org.apache.commons.lang.StringUtils;
import org.motechproject.nms.ldapbrowser.ldap.LdapRole;
import org.motechproject.nms.ldapbrowser.ldap.LdapUser;
import org.motechproject.nms.ldapbrowser.ldap.web.Views;
import org.motechproject.nms.ldapbrowser.ldap.web.actions.AbstractPageAction;
import org.motechproject.nms.ldapbrowser.support.web.LdapUserDto;

import java.io.IOException;

public class CreateUserPageAction extends AbstractPageAction {

    @Override
    public void execute() throws IOException {
        LdapUser editedUser = new LdapUser();
        LdapUser currentUser = getCurrentUser();

        setModelVariable(Views.USER_VAR, new LdapUserDto());
        setModelVariable(USER_ADMIN_MODE, currentUser.getRoles().contains(new LdapRole(StringUtils.EMPTY, StringUtils.EMPTY, true)));
        addRegionalDataToModel(currentUser, editedUser);

        printView(Views.USER_EDIT_VIEW);
    }
}
