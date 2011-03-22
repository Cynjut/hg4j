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
package org.tmatesoft.hg.test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgChangeset;
import org.tmatesoft.hg.core.HgLogCommand;
import org.tmatesoft.hg.core.HgLogCommand.CollectHandler;
import org.tmatesoft.hg.core.HgLogCommand.FileHistoryHandler;
import org.tmatesoft.hg.core.HgLogCommand.FileRevision;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.test.LogOutputParser.Record;
import org.tmatesoft.hg.util.Path;


/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestHistory {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	private HgRepository repo;
	private final ExecHelper eh;
	private LogOutputParser changelogParser;
	
	public static void main(String[] args) throws Throwable {
		TestHistory th = new TestHistory();
		th.testCompleteLog();
		th.testFollowHistory();
		th.errorCollector.verify();
//		th.testPerformance();
		th.testOriginalTestLogRepo();
		th.testUsernames();
		th.testBranches();
		//
		th.errorCollector.verify();
	}
	
	public TestHistory() throws Exception {
		this(new HgLookup().detectFromWorkingDir());
	}

	private TestHistory(HgRepository hgRepo) {
		repo = hgRepo;
		eh = new ExecHelper(changelogParser = new LogOutputParser(true), null);
		
	}

	@Test
	public void testCompleteLog() throws Exception {
		changelogParser.reset();
		eh.run("hg", "log", "--debug");
		List<HgChangeset> r = new HgLogCommand(repo).execute();
		report("hg log - COMPLETE REPO HISTORY", r, true); 
	}
	
	@Test
	public void testFollowHistory() throws Exception {
		final Path f = Path.create("cmdline/org/tmatesoft/hg/console/Remote.java");
		try {
			if (repo.getFileNode(f).exists()) { // FIXME getFileNode shall not fail with IAE
				changelogParser.reset();
				eh.run("hg", "log", "--debug", "--follow", f.toString());
				
				class H extends CollectHandler implements FileHistoryHandler {
					boolean copyReported = false;
					boolean fromMatched = false;
					public void copy(FileRevision from, FileRevision to) {
						copyReported = true;
						fromMatched = "src/com/tmate/hgkit/console/Remote.java".equals(from.getPath().toString());
					}
				};
				H h = new H();
				new HgLogCommand(repo).file(f, true).execute(h);
				String what = "hg log - FOLLOW FILE HISTORY";
				errorCollector.checkThat(what + "#copyReported ", h.copyReported, is(true));
				errorCollector.checkThat(what + "#copyFromMatched", h.fromMatched, is(true));
				//
				// cmdline always gives in changesets in order from newest (bigger rev number) to oldest.
				// LogCommand does other way round, from oldest to newest, follewed by revisions of copy source, if any
				// (apparently older than oldest of the copy target). Hence need to sort Java results according to rev numbers
				final LinkedList<HgChangeset> sorted = new LinkedList<HgChangeset>(h.getChanges());
				Collections.sort(sorted, new Comparator<HgChangeset>() {
					public int compare(HgChangeset cs1, HgChangeset cs2) {
						return cs1.getRevision() < cs2.getRevision() ? 1 : -1;
					}
				});
				report(what, sorted, false);
			}
		} catch (IllegalArgumentException ex) {
			System.out.println("Can't test file history with follow because need to query specific file with history");
		}
	}

	private void report(String what, List<HgChangeset> r, boolean reverseConsoleResults) {
		final List<Record> consoleResult = changelogParser.getResult();
		if (reverseConsoleResults) {
			Collections.reverse(consoleResult);
		}
		Iterator<Record> consoleResultItr = consoleResult.iterator();
		for (HgChangeset cs : r) {
			Record cr = consoleResultItr.next();
			int x = cs.getRevision() == cr.changesetIndex ? 0x1 : 0;
			x |= cs.getDate().equals(cr.date) ? 0x2 : 0;
			x |= cs.getNodeid().toString().equals(cr.changesetNodeid) ? 0x4 : 0;
			x |= cs.getUser().equals(cr.user) ? 0x8 : 0;
			x |= cs.getComment().equals(cr.description) ? 0x10 : 0;
			errorCollector.checkThat(String.format(what + ". Error in %d hg4j rev comparing to %d cmdline's.", cs.getRevision(), cr.changesetIndex), x, equalTo(0x1f));
			consoleResultItr.remove();
		}
		errorCollector.checkThat(what + ". Insufficient results from Java ", consoleResultItr.hasNext(), equalTo(false));
	}

	public void testPerformance() throws Exception {
		final int runs = 10;
		final long start1 = System.currentTimeMillis();
		for (int i = 0; i < runs; i++) {
			changelogParser.reset();
			eh.run("hg", "log", "--debug");
		}
		final long start2 = System.currentTimeMillis();
		for (int i = 0; i < runs; i++) {
			new HgLogCommand(repo).execute();
		}
		final long end = System.currentTimeMillis();
		System.out.printf("'hg log --debug', %d runs: Native client total %d (%d per run), Java client %d (%d)\n", runs, start2-start1, (start2-start1)/runs, end-start2, (end-start2)/runs);
	}

	@Test
	public void testOriginalTestLogRepo() throws Exception {
		repo = Configuration.get().find("log-1");
		HgLogCommand cmd = new HgLogCommand(repo);
		// funny enough, but hg log -vf a -R c:\temp\hg\test-log\a doesn't work, while --cwd <same> works fine
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "a", "--cwd", repo.getLocation());
		report("log a", cmd.file("a", false).execute(), true);
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-f", "a", "--cwd", repo.getLocation());
		List<HgChangeset> r = cmd.file("a", true).execute();
		report("log -f a", r, true);
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-f", "e", "--cwd", repo.getLocation());
		report("log -f e", cmd.file("e", true).execute(), false /*#1, below*/);
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "dir/b", "--cwd", repo.getLocation());
		report("log dir/b", cmd.file("dir/b", false).execute(), true);
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-f", "dir/b", "--cwd", repo.getLocation());
		report("log -f dir/b", cmd.file("dir/b", true).execute(), false /*#1, below*/);
		/*
		 * #1: false works because presently commands dispatches history of the queried file, and then history
		 * of it's origin. With history comprising of renames only, this effectively gives reversed (newest to oldest) 
		 * order of revisions. 
		 */
	}

	@Test
	public void testUsernames() throws Exception {
		repo = Configuration.get().find("log-users");
		final String user1 = "User One <user1@example.org>";
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-u", user1, "--cwd", repo.getLocation());
		report("log -u " + user1, new HgLogCommand(repo).user(user1).execute(), true);
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-u", "user1", "-u", "user2", "--cwd", repo.getLocation());
		report("log -u user1 -u user2", new HgLogCommand(repo).user("user1").user("user2").execute(), true);
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-u", "user3", "--cwd", repo.getLocation());
		report("log -u user3", new HgLogCommand(repo).user("user3").execute(), true);
	}

	@Test
	public void testBranches() throws Exception {
		repo = Configuration.get().find("log-branches");
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-b", "default", "--cwd", repo.getLocation());
		report("log -b default" , new HgLogCommand(repo).branch("default").execute(), true);
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-b", "test", "--cwd", repo.getLocation());
		report("log -b test" , new HgLogCommand(repo).branch("test").execute(), true);
		//
		assertTrue("log -b dummy shall yeild empty result", new HgLogCommand(repo).branch("dummy").execute().isEmpty());
		//
		changelogParser.reset();
		eh.run("hg", "log", "--debug", "-b", "default", "-b", "test", "--cwd", repo.getLocation());
		report("log -b default -b test" , new HgLogCommand(repo).branch("default").branch("test").execute(), true);
	}
}