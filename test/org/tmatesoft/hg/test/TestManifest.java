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
 * contact TMate Software at support@svnkit.com
 */
package org.tmatesoft.hg.test;

import static com.tmate.hgkit.ll.HgRepository.TIP;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

import org.tmatesoft.hg.core.LogCommand.FileRevision;
import org.tmatesoft.hg.core.Path;
import org.tmatesoft.hg.core.RepositoryTreeWalker;

import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.HgRepository;
import com.tmate.hgkit.ll.Nodeid;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestManifest {

	private final HgRepository repo;
	private ManifestOutputParser manifestParser;
	private ExecHelper eh;
	final LinkedList<FileRevision> revisions = new LinkedList<FileRevision>();
	private RepositoryTreeWalker.Handler handler  = new RepositoryTreeWalker.Handler() {
		
		public void file(FileRevision fileRevision) {
			revisions.add(fileRevision);
		}
		
		public void end(Nodeid manifestRevision) {}
		public void dir(Path p) {}
		public void begin(Nodeid manifestRevision) {}
	};

	public static void main(String[] args) throws Exception {
		HgRepository repo = new RepositoryLookup().detectFromWorkingDir();
		TestManifest tm = new TestManifest(repo);
		tm.testTip();
		tm.testFirstRevision();
		tm.testRevisionInTheMiddle();
	}

	public TestManifest(HgRepository hgRepo) {
		repo = hgRepo;
		eh = new ExecHelper(manifestParser = new ManifestOutputParser(), null);
	}
	
	public void testTip() throws Exception {
		testRevision(TIP);
	}

	public void testFirstRevision() throws Exception {
		testRevision(0);
	}
	
	public void testRevisionInTheMiddle() throws Exception {
		int rev = repo.getManifest().getRevisionCount() / 2;
		if (rev == 0) {
			throw new IllegalStateException("Need manifest with few revisions");
		}
		testRevision(rev);
	}

	private void testRevision(int rev) throws Exception {
		manifestParser.reset();
		eh.run("hg", "manifest", "--debug", "--rev", String.valueOf(rev));
		revisions.clear();
		new RepositoryTreeWalker(repo).revision(rev).walk(handler);
		report("manifest " + (rev == TIP ? "TIP:" : "--rev " + rev));
	}

	private void report(String what) throws Exception {
		final Map<Path, Nodeid> cmdLineResult = new LinkedHashMap<Path, Nodeid>(manifestParser.getResult());
		boolean error = false;
		for (FileRevision fr : revisions) {
			Nodeid nid = cmdLineResult.remove(fr.getPath());
			if (nid == null) {
				System.out.println("Extra " + fr.getPath() + " in Java result");
				error = true;
			} else {
				if (!nid.equals(fr.getRevision())) {
					System.out.println("Non-matching nodeid:" + nid);
					error = true;
				}
			}
		}
		if (!cmdLineResult.isEmpty()) {
			System.out.println("Non-matched entries from command line:");
			error = true;
			for (Path p : cmdLineResult.keySet()) {
				System.out.println(p);
			}
		}
		System.out.println(what + (error ? " ERROR" : " OK"));
	}
}
