/*
 * Copyright (c) 2011 TMate Software Ltd
 *  
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * For information on how to redistribute this software under
 * the terms of a license other than GNU General Public License
 * contact TMate Software at support@hg4j.com
 */
package org.tmatesoft.hg.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.tmatesoft.hg.internal.RepositoryComparator;
import org.tmatesoft.hg.internal.RepositoryComparator.BranchChain;
import org.tmatesoft.hg.repo.HgBundle;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.CancelledException;

/**
 * Command to find out changes available in a remote repository, missing locally.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgIncomingCommand {

	private final HgRepository localRepo;
	private HgRemoteRepository remoteRepo;
	@SuppressWarnings("unused")
	private boolean includeSubrepo;
	private RepositoryComparator comparator;
	private List<BranchChain> missingBranches;
	private HgChangelog.ParentWalker parentHelper;
	private Set<String> branches;

	public HgIncomingCommand(HgRepository hgRepo) {
	 	localRepo = hgRepo;
	}
	
	public HgIncomingCommand against(HgRemoteRepository hgRemote) {
		remoteRepo = hgRemote;
		comparator = null;
		missingBranches = null;
		return this;
	}

	/**
	 * Select specific branch to push.
	 * Multiple branch specification possible (changeset from any of these would be included in result).
	 * Note, {@link #executeLite(Object)} does not respect this setting.
	 * 
	 * @param branch - branch name, case-sensitive, non-null.
	 * @return <code>this</code> for convenience
	 * @throws IllegalArgumentException when branch argument is null
	 */
	public HgIncomingCommand branch(String branch) {
		if (branch == null) {
			throw new IllegalArgumentException();
		}
		if (branches == null) {
			branches = new TreeSet<String>();
		}
		branches.add(branch);
		return this;
	}
	
	/**
	 * PLACEHOLDER, NOT IMPLEMENTED YET.
	 * 
	 * Whether to include sub-repositories when collecting changes, default is <code>true</code> XXX or false?
	 * @return <code>this</code> for convenience
	 */
	public HgIncomingCommand subrepo(boolean include) {
		includeSubrepo = include;
		throw HgRepository.notImplemented();
	}

	/**
	 * Lightweight check for incoming changes, gives only list of revisions to pull.
	 * Reported changes are from any branch (limits set by {@link #branch(String)} are not taken into account. 
	 *   
	 * @param context anything hg4j can use to get progress and/or cancel support
	 * @return list of nodes present at remote and missing locally
	 * @throws HgException
	 * @throws CancelledException
	 */
	public List<Nodeid> executeLite(Object context) throws HgException, CancelledException {
		LinkedHashSet<Nodeid> result = new LinkedHashSet<Nodeid>();
		RepositoryComparator repoCompare = getComparator(context);
		for (BranchChain bc : getMissingBranches(context)) {
			List<Nodeid> missing = repoCompare.visitBranches(bc);
			HashSet<Nodeid> common = new HashSet<Nodeid>(); // ordering is irrelevant  
			repoCompare.collectKnownRoots(bc, common);
			// missing could only start with common elements. Once non-common, rest is just distinct branch revision trails.
			for (Iterator<Nodeid> it = missing.iterator(); it.hasNext() && common.contains(it.next()); it.remove()) ; 
			result.addAll(missing);
		}
		ArrayList<Nodeid> rv = new ArrayList<Nodeid>(result);
		return rv;
	}

	/**
	 * Full information about incoming changes
	 * 
	 * @throws HgException
	 * @throws CancelledException
	 */
	public void executeFull(final HgLogCommand.Handler handler) throws HgException, CancelledException {
		if (handler == null) {
			throw new IllegalArgumentException("Delegate can't be null");
		}
		final List<Nodeid> common = getCommon(handler);
		HgBundle changegroup = remoteRepo.getChanges(common);
		try {
			changegroup.changes(localRepo, new HgChangelog.Inspector() {
				private int localIndex = -1; // in case we start with empty repo and localIndex would not get initialized in regular way
				private final HgChangelog.ParentWalker parentHelper;
				private final ChangesetTransformer transformer;
				private final HgChangelog changelog;
				
				{
					transformer = new ChangesetTransformer(localRepo, handler, getParentHelper());
					transformer.limitBranches(branches);
					parentHelper = getParentHelper();
					changelog = localRepo.getChangelog();
				}
				
				public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
					if (parentHelper.knownNode(nodeid)) {
						if (!common.contains(nodeid)) {
							throw new HgBadStateException("Bundle shall not report known nodes other than roots we've supplied");
						}
						localIndex = changelog.getLocalRevision(nodeid);
						return;
					}
					transformer.next(++localIndex, nodeid, cset);
				}
			});
		} catch (IOException ex) {
			throw new HgException(ex);
		}
	}

	private RepositoryComparator getComparator(Object context) throws HgException, CancelledException {
		if (remoteRepo == null) {
			throw new HgBadArgumentException("Shall specify remote repository to compare against", null);
		}
		if (comparator == null) {
			comparator = new RepositoryComparator(getParentHelper(), remoteRepo);
//			comparator.compare(context); // XXX meanwhile we use distinct path to calculate common  
		}
		return comparator;
	}
	
	private HgChangelog.ParentWalker getParentHelper() {
		if (parentHelper == null) {
			parentHelper = localRepo.getChangelog().new ParentWalker();
			parentHelper.init();
		}
		return parentHelper;
	}
	
	private List<BranchChain> getMissingBranches(Object context) throws HgException, CancelledException {
		if (missingBranches == null) {
			missingBranches = getComparator(context).calculateMissingBranches();
		}
		return missingBranches;
	}

	private List<Nodeid> getCommon(Object context) throws HgException, CancelledException {
//		return getComparator(context).getCommon();
		final LinkedHashSet<Nodeid> common = new LinkedHashSet<Nodeid>();
		// XXX common can be obtained from repoCompare, but at the moment it would almost duplicate work of calculateMissingBranches
		// once I refactor latter, common shall be taken from repoCompare.
		RepositoryComparator repoCompare = getComparator(context);
		for (BranchChain bc : getMissingBranches(context)) {
			repoCompare.collectKnownRoots(bc, common);
		}
		return new LinkedList<Nodeid>(common);
	}
}
