/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.settingsRepository.test

import com.intellij.mock.MockVirtualFileSystem
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.impl.stores.StreamProvider
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.merge.MergeSession
import com.intellij.openapi.vfs.CharsetToolkit
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.PathUtilRt
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
import org.jetbrains.jgit.dirCache.AddFile
import org.jetbrains.jgit.dirCache.deletePath
import org.jetbrains.jgit.dirCache.edit
import org.jetbrains.jgit.dirCache.writePath
import org.jetbrains.settingsRepository.CannotResolveConflictInTestMode
import org.jetbrains.settingsRepository.SyncType
import org.jetbrains.settingsRepository.conflictResolver
import org.jetbrains.settingsRepository.git.GitRepositoryManager
import org.jetbrains.settingsRepository.git.commit
import org.jetbrains.settingsRepository.git.computeIndexDiff
import org.jetbrains.settingsRepository.git.resetHard
import org.junit.Assert.assertThat
import org.junit.Test
import java.io.File
import java.util.Arrays
import kotlin.properties.Delegates

// kotlin bug, cannot be val (.NoSuchMethodError: org.jetbrains.settingsRepository.SettingsRepositoryPackage.getMARKER_ACCEPT_MY()[B)
object AM {
  val MARKER_ACCEPT_MY: ByteArray = "__accept my__".toByteArray()
  val MARKER_ACCEPT_THEIRS: ByteArray = "__accept theirs__".toByteArray()
}

class GitTest : TestCase() {
  private val repositoryManager: GitRepositoryManager
    get() = icsManager.repositoryManager as GitRepositoryManager

  private val repository: Repository
    get() = repositoryManager.repository

  val remoteRepository by Delegates.lazy {
    tempDirManager.createRepository("upstream")
  }

  init {
    conflictResolver = { files, mergeProvider ->
      val mergeSession = mergeProvider.createMergeSession(files)
      for (file in files) {
        val mergeData = mergeProvider.loadRevisions(file)
        if (Arrays.equals(mergeData.CURRENT, AM.MARKER_ACCEPT_MY) || Arrays.equals(mergeData.LAST, AM.MARKER_ACCEPT_THEIRS)) {
          mergeSession.conflictResolvedForFile(file, MergeSession.Resolution.AcceptedYours)
        }
        else if (Arrays.equals(mergeData.CURRENT, AM.MARKER_ACCEPT_THEIRS) || Arrays.equals(mergeData.LAST, AM.MARKER_ACCEPT_MY)) {
          mergeSession.conflictResolvedForFile(file, MergeSession.Resolution.AcceptedTheirs)
        }
        else if (Arrays.equals(mergeData.LAST, AM.MARKER_ACCEPT_MY)) {
          file.setBinaryContent(mergeData.LAST!!)
          mergeProvider.conflictResolvedForFile(file)
        }
        else {
          throw CannotResolveConflictInTestMode()
        }
      }
    }
  }

  private fun delete(data: ByteArray, directory: Boolean) {
    val addedFile = "remote.xml"
    provider.save(addedFile, data)
    provider.delete(if (directory) "" else addedFile, RoamingType.PER_USER)

    val diff = repository.computeIndexDiff()
    assertThat(diff.diff(), equalTo(false))
    assertThat(diff.getAdded(), empty())
    assertThat(diff.getChanged(), empty())
    assertThat(diff.getRemoved(), empty())
    assertThat(diff.getModified(), empty())
    assertThat(diff.getUntracked(), empty())
    assertThat(diff.getUntrackedFolders(), empty())
  }

  private fun addAndCommit(path: String): FileInfo {
    val data = FileUtil.loadFileBytes(File(testDataPath, PathUtilRt.getFileName(path)))
    provider.save(path, data)
    repositoryManager.commit(EmptyProgressIndicator())
    return FileInfo(path, data)
  }

  public Test fun add() {
    val data = FileUtil.loadFileBytes(File(testDataPath, "remote.xml"))
    val addedFile = "remote.xml"
    provider.save(addedFile, data)

    val diff = repository.computeIndexDiff()
    assertThat(diff.diff(), equalTo(true))
    assertThat(diff.getAdded(), contains(equalTo(addedFile)))
    assertThat(diff.getChanged(), empty())
    assertThat(diff.getRemoved(), empty())
    assertThat(diff.getModified(), empty())
    assertThat(diff.getUntracked(), empty())
    assertThat(diff.getUntrackedFolders(), empty())
  }

  public Test fun addSeveral() {
    val data = FileUtil.loadFileBytes(File(testDataPath, "remote.xml"))
    val data2 = FileUtil.loadFileBytes(File(testDataPath, "local.xml"))
    val addedFile = "remote.xml"
    val addedFile2 = "local.xml"
    provider.save(addedFile, data)
    provider.save(addedFile2, data2)

    val diff = repository.computeIndexDiff()
    assertThat(diff.diff(), equalTo(true))
    assertThat(diff.getAdded(), contains(equalTo(addedFile), equalTo(addedFile2)))
    assertThat(diff.getChanged(), empty())
    assertThat(diff.getRemoved(), empty())
    assertThat(diff.getModified(), empty())
    assertThat(diff.getUntracked(), empty())
    assertThat(diff.getUntrackedFolders(), empty())
  }

  public Test fun delete() {
    val data = FileUtil.loadFileBytes(File(testDataPath, "remote.xml"))
    delete(data, false)
    delete(data, true)
  }

  public Test fun setUpstream() {
    val url = "https://github.com/user/repo.git"
    repositoryManager.setUpstream(url, null)
    assertThat(repositoryManager.getUpstream(), equalTo(url))
  }

  Test
  public fun pullToRepositoryWithoutCommits() {
    doPullToRepositoryWithoutCommits(null)
  }

  public Test fun pullToRepositoryWithoutCommitsAndCustomRemoteBranchName() {
    doPullToRepositoryWithoutCommits("customRemoteBranchName")
  }

  private fun doPullToRepositoryWithoutCommits(remoteBranchName: String?) {
    createLocalRepository(remoteBranchName)
    repositoryManager.pull(EmptyProgressIndicator())
    compareFiles(repository.getWorkTree(), remoteRepository.getWorkTree())
  }

  public Test fun pullToRepositoryWithCommits() {
    doPullToRepositoryWithCommits(null)
  }

  public Test fun pullToRepositoryWithCommitsAndCustomRemoteBranchName() {
    doPullToRepositoryWithCommits("customRemoteBranchName")
  }

  private fun doPullToRepositoryWithCommits(remoteBranchName: String?) {
    val file = createLocalRepositoryAndCommit(remoteBranchName)

    val progressIndicator = EmptyProgressIndicator()
    repositoryManager.commit(progressIndicator)
    repositoryManager.pull(progressIndicator)
    assertThat(FileUtil.loadFile(File(repository.getWorkTree(), file.name)), equalTo(String(file.data, CharsetToolkit.UTF8_CHARSET)))
    compareFiles(repository.getWorkTree(), remoteRepository.getWorkTree(), null, PathUtilRt.getFileName(file.name))
  }
  
  private fun createLocalRepository(remoteBranchName: String?) {
    createFileRemote(remoteBranchName)
    repositoryManager.setUpstream(remoteRepository.getWorkTree().getAbsolutePath(), remoteBranchName)
  }

  private fun createLocalRepositoryAndCommit(remoteBranchName: String? = null): FileInfo {
    createLocalRepository(remoteBranchName)
    return addAndCommit("local.xml")
  }

  private fun compareFiles(fs: MockVirtualFileSystem) {
    compareFiles(fs.getRoot())
  }

  private fun compareFiles(expected: VirtualFile?) {
    compareFiles(repository.getWorkTree(), remoteRepository.getWorkTree(), expected)
  }

  // never was merged. we reset using "merge with strategy "theirs", so, we must test - what's happen if it is not first merge? - see next test
  public Test fun resetToTheirsIfFirstMerge() {
    createLocalRepositoryAndCommit(null)
    sync(SyncType.OVERWRITE_LOCAL)
    compareFiles(fs("remote.xml"))
  }

  public Test fun resetToTheirsISecondMergeIsNull() {
    createLocalRepositoryAndCommit(null)
    sync(SyncType.MERGE)

    restoreRemoteAfterPush()

    val fs = MockVirtualFileSystem()

    fun testRemote() {
      fs.findFileByPath("local.xml")
      fs.findFileByPath("remote.xml")
      compareFiles(fs.getRoot())
    }
    testRemote()

    addAndCommit("_mac/local2.xml")
    sync(SyncType.OVERWRITE_LOCAL)

    compareFiles(fs.getRoot())

    // test: merge and push to remote after such reset
    sync(SyncType.MERGE)

    restoreRemoteAfterPush()

    testRemote()
  }

  public Test fun resetToMyIfFirstMerge() {
    createLocalRepositoryAndCommit(null)
    sync(SyncType.OVERWRITE_REMOTE)
    restoreRemoteAfterPush()
    compareFiles(fs("local.xml"))
  }

  public Test fun `reset to my, second merge is null`() {
    createLocalRepositoryAndCommit()
    sync(SyncType.MERGE)

    restoreRemoteAfterPush()

    val fs = fs("local.xml", "remote.xml")
    compareFiles(fs)

    val localToFilePath = "_mac/local2.xml"
    addAndCommit(localToFilePath)
    sync(SyncType.OVERWRITE_REMOTE)
    restoreRemoteAfterPush()

    fs.findFileByPath(localToFilePath)
    compareFiles(fs)

    // test: merge to remote after such reset
    sync(SyncType.MERGE)

    restoreRemoteAfterPush()

    compareFiles(fs)
  }

  public Test fun `merge - resolve conflicts to my`() {
    createLocalRepository(null)

    val data = AM.MARKER_ACCEPT_MY
    provider.save("remote.xml", data)

    sync(SyncType.MERGE)

    restoreRemoteAfterPush()
    compareFiles(fs("remote.xml"))
  }

  public Test fun `merge - theirs file deleted, my modified, accept theirs`() {
    createLocalRepository(null)

    sync(SyncType.MERGE)

    val data = AM.MARKER_ACCEPT_THEIRS
    provider.save("remote.xml", data)
    repositoryManager.commit(EmptyProgressIndicator())

    remoteRepository.deletePath("remote.xml")
    remoteRepository.commit("delete remote.xml")

    sync(SyncType.MERGE)

    compareFiles(fs())
  }

  public Test fun `merge - my file deleted, theirs modified, accept my`() {
    createLocalRepository(null)

    sync(SyncType.MERGE)

    provider.delete("remote.xml", RoamingType.PER_USER)
    repositoryManager.commit(EmptyProgressIndicator())

    remoteRepository.writePath("remote.xml", AM.MARKER_ACCEPT_THEIRS)
    remoteRepository.commit("")

    sync(SyncType.MERGE)
    restoreRemoteAfterPush()

    compareFiles(fs())
  }

  Test fun `commit if unmerged`() {
    createLocalRepository(null)

    provider.saveContent("remote.xml", "<foo />")

    try {
      sync(SyncType.MERGE)
    }
    catch (e: CannotResolveConflictInTestMode) {
    }

    // repository in unmerged state
    conflictResolver = {files, mergeProvider ->
      val mergeSession = mergeProvider.createMergeSession(files)
      mergeSession.conflictResolvedForFile(files.first(), MergeSession.Resolution.AcceptedTheirs)
    }
    sync(SyncType.MERGE)

    compareFiles(fs("remote.xml"))
  }

  // remote is uninitialized (empty - initial commit is not done)
  public Test fun `merge with uninitialized upstream`() {
    doSyncWithUninitializedUpstream(SyncType.MERGE)
  }

  public Test fun `reset to my, uninitialized upstream`() {
    doSyncWithUninitializedUpstream(SyncType.OVERWRITE_REMOTE)
  }

  public Test fun `reset to theirs, uninitialized upstream`() {
    doSyncWithUninitializedUpstream(SyncType.OVERWRITE_LOCAL)
  }

  fun createFileRemote(branchName: String? = null, initialCommit: Boolean = true): File {
    val repository = getRemoteRepository(branchName)

    val workTree: File = repository.getWorkTree()
    if (initialCommit) {
      val addedFile = "remote.xml"
      FileUtil.copy(File(testDataPath, "remote.xml"), File(workTree, addedFile))
      repository.edit(AddFile(addedFile))
      repository.commit("")
    }
    return workTree
  }

  fun getRemoteRepository(branchName: String? = null): Repository {
    val repository = remoteRepository
    if (branchName != null) {
      // jgit cannot checkout&create branch if no HEAD (no commits in our empty repository), so we create initial empty commit
      repository.commit("")
      Git(repository).checkout().setCreateBranch(true).setName(branchName).call()
    }
    return repository
  }

  private fun doSyncWithUninitializedUpstream(syncType: SyncType) {
    createFileRemote(null, false)
    repositoryManager.setUpstream(remoteRepository.getWorkTree().getAbsolutePath(), null)

    val path = "local.xml"
    val data = FileUtil.loadFileBytes(File(testDataPath, PathUtilRt.getFileName(path)))
    provider.save(path, data)

    sync(syncType)

    val fs = MockVirtualFileSystem()
    if (syncType != SyncType.OVERWRITE_LOCAL) {
      fs.findFileByPath(path)
    }
    restoreRemoteAfterPush();
    compareFiles(fs)
  }

  private fun restoreRemoteAfterPush() {
    /** we must not push to non-bare repository - but we do it in test (our sync merge equals to "pull&push"),
    "
    By default, updating the current branch in a non-bare repository
    is denied, because it will make the index and work tree inconsistent
    with what you pushed, and will require 'git reset --hard' to match the work tree to HEAD.
    "
    so, we do "git reset --hard"
     */
    remoteRepository.resetHard()
  }

  private fun sync(syncType: SyncType) {
    icsManager.sync(syncType, fixtureManager.projectFixture.getProject())
  }
}

fun StreamProvider.saveContent(fileSpec: String, content: String) {
  val data = content.toByteArray()
  saveContent(fileSpec, data, data.size(), RoamingType.PER_USER, false)
}