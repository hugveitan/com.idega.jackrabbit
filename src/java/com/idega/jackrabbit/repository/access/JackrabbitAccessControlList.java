package com.idega.jackrabbit.repository.access;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlException;
import javax.jcr.security.Privilege;

import org.springframework.beans.factory.annotation.Autowired;

import com.idega.core.accesscontrol.dao.PermissionDAO;
import com.idega.core.accesscontrol.data.bean.ICPermission;
import com.idega.repository.access.AccessControlList;
import com.idega.repository.access.RepositoryPrivilege;
import com.idega.util.ArrayUtil;
import com.idega.util.ListUtil;
import com.idega.util.expression.ELUtil;

public class JackrabbitAccessControlList implements org.apache.jackrabbit.api.security.JackrabbitAccessControlList, AccessControlList {

	@Autowired
	private PermissionDAO permissionDAO;

	private String path;
	private List<ICPermission> permissions;

	public JackrabbitAccessControlList(String path) {
		this.path = path;

		loadPermissions();
	}

	@Override
	public List<ICPermission> getPermissions() {
		if (permissions == null)
			loadPermissions();
		return permissions;
	}

	private PermissionDAO getPermissionDAO() {
		if (permissionDAO == null)
			ELUtil.getInstance().autowire(this);
		return permissionDAO;
	}

	private void loadPermissions() {
		List<ICPermission> permissions = getPermissionDAO().findPermissions(getPath());
		this.permissions = permissions == null ? null : new ArrayList<ICPermission>(permissions);
	}

	@Override
	public String getPath() {
		return path;
	}

	@Override
	public AccessControlEntry[] getAccessControlEntries() throws RepositoryException {
		List<ICPermission> permissions = getPermissions();
		if (ListUtil.isEmpty(permissions))
			return null;

		Map<String, List<Privilege>> privileges = new HashMap<String, List<Privilege>>();
		for (ICPermission permission: permissions) {
			if (permission.isActive() && permission.getPermissionValue()) {
				String principal = permission.getContextValue();	//	Role name
				String perm = permission.getPermissionString();		//	Privilege
				List<Privilege> priv = privileges.get(principal);
				if (priv == null) {
					priv = new ArrayList<Privilege>();
					privileges.put(principal, priv);
				}
				priv.add(new RepositoryPrivilege(perm));
			}
		}

		List<AccessControlEntry> entries = new ArrayList<AccessControlEntry>();
		for (String principal: privileges.keySet()) {
			final String principalName = principal;
			AccessControlEntry entry = new JackrabbitAccessControlEntry(new Principal() {
				@Override
				public String getName() {
					return principalName;
				}
			}, ArrayUtil.convertListToArray(privileges.get(principal)));
			entries.add(entry);
		}

		return ArrayUtil.convertListToArray(entries);
	}

	@Override
	public boolean addAccessControlEntry(Principal principal, Privilege[] privileges) throws AccessControlException, RepositoryException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void removeAccessControlEntry(AccessControlEntry ace) throws AccessControlException, RepositoryException {
		// TODO Auto-generated method stub

	}

	@Override
	public String[] getRestrictionNames() throws RepositoryException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getRestrictionType(String restrictionName) throws RepositoryException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public boolean isEmpty() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public int size() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public boolean addEntry(Principal principal, Privilege[] privileges, boolean isAllow) throws AccessControlException, RepositoryException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean addEntry(Principal principal, Privilege[] privileges, boolean isAllow, Map<String, Value> restrictions)
			throws AccessControlException, RepositoryException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void orderBefore(AccessControlEntry srcEntry, AccessControlEntry destEntry)
			throws AccessControlException, UnsupportedRepositoryOperationException, RepositoryException {
		// TODO Auto-generated method stub

	}

	@Override
	public String getResourcePath() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void add(com.idega.repository.access.AccessControlEntry ace) {
		// TODO Auto-generated method stub

	}

}