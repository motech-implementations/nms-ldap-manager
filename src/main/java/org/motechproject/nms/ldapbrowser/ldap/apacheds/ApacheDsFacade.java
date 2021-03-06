package org.motechproject.nms.ldapbrowser.ldap.apacheds;

import edu.emory.mathcs.backport.java.util.Arrays;
import org.apache.commons.lang.StringUtils;
import org.apache.directory.api.ldap.model.cursor.CursorException;
import org.apache.directory.api.ldap.model.cursor.EntryCursor;
import org.apache.directory.api.ldap.model.entry.DefaultModification;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.entry.Modification;
import org.apache.directory.api.ldap.model.entry.ModificationOperation;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.apache.directory.api.ldap.model.message.AddRequest;
import org.apache.directory.api.ldap.model.message.AddRequestImpl;
import org.apache.directory.api.ldap.model.message.AddResponse;
import org.apache.directory.api.ldap.model.message.ModifyRequest;
import org.apache.directory.api.ldap.model.message.ModifyRequestImpl;
import org.apache.directory.api.ldap.model.message.ModifyResponse;
import org.apache.directory.api.ldap.model.message.ResultCodeEnum;
import org.apache.directory.api.ldap.model.message.ResultResponse;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.api.LdapConnectionPool;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.motechproject.nms.ldapbrowser.ldap.LdapFacade;
import org.motechproject.nms.ldapbrowser.ldap.LdapRole;
import org.motechproject.nms.ldapbrowser.ldap.LdapUser;
import org.motechproject.nms.ldapbrowser.ldap.RoleType;
import org.motechproject.nms.ldapbrowser.ldap.ex.LdapAuthException;
import org.motechproject.nms.ldapbrowser.ldap.ex.LdapReadException;
import org.motechproject.nms.ldapbrowser.ldap.ex.LdapWriteException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ApacheDsFacade implements LdapFacade {

    private static final Logger LOG = LoggerFactory.getLogger(ApacheDsFacade.class);

    private static final String EXCEPTION = "Exception:";

    private String ldapHost;
    private int ldapPort;
    private boolean ldapUseSsl;
    private LdapConnectionPool adminConnectionPool;
    private EntryHelper entryHelper;

    @Override
    public LdapUser findUser(String username, String adminUsername, String password) {
        LdapConnection connection = null;

        try {
            connection = getConnectionForUser(adminUsername, password);
            EntryCursor cursor = entryHelper.searchForAdmins(connection);

            while (cursor.next()) {
                Entry entry = cursor.get();
                String ldapUserName = entryHelper.getUsername(entry);
                if (username.equals(ldapUserName)) {
                    return entryHelper.buildUser(entry, entryHelper.getAllRolesCursor(connection));
                }
            }
            return null;
        } catch (LdapException | CursorException e) {
            throw new LdapAuthException("Unable to search for users in LDAP", e);
        } finally {
            ConnectionUtils.unbind(connection);
        }
    }

    @Override
    public boolean isAdminUser(String username) {
        LdapConnection connection = null;

        try {
            connection = adminConnectionPool.getConnection();
            EntryCursor cursor = entryHelper.searchForAdmins(connection);

            while (cursor.next()) {
                Entry entry = cursor.get();
                String ldapUserName = entryHelper.getUsername(entry);
                if (username.equals(ldapUserName)) {
                    ApacheDsUser user = entryHelper.buildUser(entry, entryHelper.getAllRolesCursor(connection));
                    for (LdapRole role : user.getRoles()) {
                        if (role.isAdmin()) {
                            return true;
                        }
                    }
                    return false;
                }
            }

            return false;
        } catch (LdapException | CursorException e) {
            throw new LdapAuthException("Unable to bind admin connection or verify user rights in LDAP", e);
        } finally {
            ConnectionUtils.releaseConnection(adminConnectionPool, connection);
        }
    }

    @Override
    public void addLdapUserEntry(LdapUser user, String creatorUsername, String creatorPassword) {
        LdapConnection connection = null;

        try {
            connection = getConnectionForUser(creatorUsername, creatorPassword);

            List<String> errorMessages = new ArrayList<>();

            if (StringUtils.isNotBlank(user.getDistrict()) && StringUtils.isBlank(user.getState())) {
                user.setState(entryHelper.findStateForDistrict(connection, user.getDistrict()));
            }

            Entry userEntry = entryHelper.userToEntry(user);
            ApacheDsUser userPriorUpdate = (ApacheDsUser) findUser(user.getUsername(), creatorUsername, creatorPassword);
            String userDn = userPriorUpdate != null ? userPriorUpdate.getDn().toString() :
                    entryHelper.buildUserDn(user.getUsername(), user.getState(), user.getDistrict());

            ResultCodeEnum resultCode;

            if (userPriorUpdate == null) {
                // Adds user entry
                AddRequest addRequest = new AddRequestImpl();
                addRequest.setEntry(userEntry);
                AddResponse addResponse = connection.add(addRequest);
                resultCode = addResponse.getLdapResult().getResultCode();

                handleErrorMessage(errorMessages, addResponse);
            } else {
                // Updates user entry
                List<Modification> modifications = entryHelper.prepareModificationRequests(userEntry, userPriorUpdate);

                ModifyRequest request = new ModifyRequestImpl();

                for (Modification modification : modifications) {
                    request.addModification(modification);
                }

                resultCode = executeModifyRequestAndHandleErrorMessages(connection, userPriorUpdate.getDn(), request, errorMessages);
            }

            if (resultCode != ResultCodeEnum.SUCCESS) {
                throw new LdapWriteException(String.format("User %s failed to add or update user %s with dn %s. Error code: %s ",
                        creatorUsername, user.getUsername(), userEntry.getDn(), resultCode), errorMessages);
            }

            // Adds roles
            Iterator<LdapRole> it = user.getRoles().iterator();

            if (userPriorUpdate != null) {
                // UPDATE case
                List<LdapRole> userRolesPriorUpdate = userPriorUpdate.getRoles();
                while (it.hasNext()) {
                    LdapRole role = it.next();
                    if (userRolesPriorUpdate.contains(role)) {
                        //role present in both lists
                        it.remove();
                        userRolesPriorUpdate.remove(role);
                    }
                }

                for (LdapRole role : userRolesPriorUpdate) {
                    // Roles present in old user object but not present in current user object must be removed
                    String roleDn = entryHelper.buildDn(role.getState(), role.getDistrict(), role.isAdmin() ? RoleType.USER_ADMIN : RoleType.VIEWER);

                    Modification modification = new DefaultModification(ModificationOperation.REMOVE_ATTRIBUTE,
                            entryHelper.getAttributeName(role.isAdmin() ? RoleType.USER_ADMIN : RoleType.VIEWER), userDn
                    );
                    ModifyRequest request = new ModifyRequestImpl();
                    request.addModification(modification);

                    resultCode = executeModifyRequestAndHandleErrorMessages(connection, new Dn(roleDn), request, errorMessages);
                }
            }

            // Roles remaining in lists must trigger udpates
            for (LdapRole role : user.getRoles()) {
                // Roles present in new user object but not present in previous user object must be added

                //Both User Admins and Viewers get the View role
                String roleDn = entryHelper.buildDn(role.getState(), role.getDistrict(), RoleType.VIEWER);
                ModifyRequest request = new ModifyRequestImpl();
                Modification viewerModification = new DefaultModification(ModificationOperation.ADD_ATTRIBUTE,
                        entryHelper.getAttributeName(RoleType.VIEWER), userDn
                );
                request.addModification(viewerModification);
                resultCode = executeModifyRequestAndHandleErrorMessages(connection, new Dn(roleDn), request, errorMessages);

                if (role.isAdmin()) {
                    roleDn = entryHelper.buildDn(role.getState(), role.getDistrict(), RoleType.USER_ADMIN);
                    request = new ModifyRequestImpl();
                    Modification adminModification = new DefaultModification(ModificationOperation.ADD_ATTRIBUTE,
                            entryHelper.getAttributeName(RoleType.USER_ADMIN), userDn
                    );
                    request.addModification(adminModification);
                    executeModifyRequestAndHandleErrorMessages(connection, new Dn(roleDn), request, errorMessages);
                }
            }

            if (resultCode != ResultCodeEnum.SUCCESS) {
                throw new LdapWriteException(String.format("User %s failed to add or update roles of user %s with dn %s. Error code: %s ",
                        creatorUsername, user.getUsername(), userEntry.getDn(), resultCode), errorMessages);
            }

        } catch (CursorException | LdapException e) {
            throw new LdapWriteException(String.format("User %s failed to add or update user %s ",
                    creatorUsername, user.getUsername()), e, Arrays.asList(new String[]{e.getMessage()}));
        } finally {
            ConnectionUtils.unbind(connection);
        }
    }

    @Override
    public List<LdapUser> getUsers(String adminUsername, String adminPassword) {
        LdapConnection connection = null;
        try {
            connection = getConnectionForUser(adminUsername, adminPassword);

            return entryHelper.getAllUsers(connection);
        } catch (LdapException | CursorException e) {
            throw new LdapReadException("User " + adminUsername + " failed to retrieve users", e);
        } finally {
            ConnectionUtils.unbind(connection);
        }
    }

    @Override
    public void deleteUser(String username, String adminUsername, String adminPassword) {
        LdapConnection connection = null;
        try {
            connection = getConnectionForUser(adminUsername, adminPassword);

            entryHelper.deleteUser(connection, username);

        } catch (LdapException | CursorException e) {
            throw new LdapReadException("User " + adminUsername + " failed to delete user", e);
        } finally {
            ConnectionUtils.unbind(connection);
        }
    }

    @Override
    public LdapNetworkConnection getConnectionForUser(String username, String password) {
        return ConnectionUtils.getConnectionAndBindUser(ldapHost, ldapPort, ldapUseSsl,
                getDnForUsername(username), password);
    }

    @Override
    public LdapUser getLoggedUser(String currentUsername) {
        LdapConnection connection = null;

        try {
            connection = adminConnectionPool.getConnection();
            EntryCursor cursor = entryHelper.searchForAdmins(connection);

            while (cursor.next()) {
                Entry entry = cursor.get();
                String ldapUserName = entryHelper.getUsername(entry);
                if (currentUsername.equals(ldapUserName)) {
                    return entryHelper.buildUser(entry, entryHelper.getAllRolesCursor(connection));
                }
            }

        } catch (LdapException | CursorException e) {
            throw new LdapAuthException("Unable to get admin connection in LDAP", e);
        } finally {
            ConnectionUtils.releaseConnection(adminConnectionPool, connection);
        }

        return null;
    }

    private Dn getDnForUsername(String username) {
        LdapConnection connection = null;

        try {
            connection = adminConnectionPool.getConnection();
            EntryCursor cursor = entryHelper.searchForAdmins(connection);

            while (cursor.next()) {
                Entry entry = cursor.get();
                String ldapUserName = entryHelper.getUsername(entry);
                if (username.equals(ldapUserName)) {
                    return entry.getDn();
                }
            }

        } catch (LdapException | CursorException e) {
            throw new LdapAuthException("Unable to get admin connection in LDAP", e);
        } finally {
            ConnectionUtils.releaseConnection(adminConnectionPool, connection);
        }

        return null;
    }

    private ResultCodeEnum executeModifyRequestAndHandleErrorMessages(LdapConnection connection, Dn dn, ModifyRequest request, List<String> errorMessages)
            throws LdapException {

        if (!request.getModifications().isEmpty()) {
            request.setName(dn);
            ModifyResponse response = connection.modify(request);
            ResultCodeEnum resultCode = response.getLdapResult().getResultCode();
            handleErrorMessage(errorMessages, response);

            return resultCode;
        } else {
            // Nothing to modify - nothing to fail :)
            return ResultCodeEnum.SUCCESS;
        }
    }

    private void handleErrorMessage(List<String> errors, ResultResponse resultResponse) {
        if (resultResponse.getLdapResult().getResultCode() != ResultCodeEnum.SUCCESS) {
            String diagnosticMessage = resultResponse.getLdapResult().getDiagnosticMessage();

            // Do not include stacktrace in the message
            diagnosticMessage = parseDiagnosticMessage(diagnosticMessage);
            errors.add(diagnosticMessage);
        }
    }

    private String parseDiagnosticMessage(String diagnosticMessage) {
        int offset = diagnosticMessage.indexOf(EXCEPTION);
        if (offset != -1) {
            diagnosticMessage = diagnosticMessage.substring(offset + EXCEPTION.length(), diagnosticMessage.indexOf("\r\n", offset));
        }

        return diagnosticMessage;
    }

    public void setLdapHost(String ldapHost) {
        this.ldapHost = ldapHost;
    }

    public void setLdapPort(int ldapPort) {
        this.ldapPort = ldapPort;
    }

    public void setLdapUseSsl(boolean ldapUseSsl) {
        this.ldapUseSsl = ldapUseSsl;
    }

    public void setAdminConnectionPool(LdapConnectionPool adminConnectionPool) {
        this.adminConnectionPool = adminConnectionPool;
    }

    public void setEntryHelper(EntryHelper entryHelper) {
        this.entryHelper = entryHelper;
    }
}
