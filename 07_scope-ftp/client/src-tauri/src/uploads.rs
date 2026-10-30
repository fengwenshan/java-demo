//! 大文件分片上传 + 断点续传。
//!
//! - ≤ 32MB（[`MULTIPART_THRESHOLD`]）走一次 `multipart/form-data`；
//! - > 32MB 走 `init → chunk → complete`，分片大小以服务端 `init` 返回为准（默认 8MB）；
//! - 断点续传状态记录在 `app_config_dir()/uploads.json`：
//!   `{serverId, dir, fileName, size, mtime, uploadId, sha256, createdAt}`；
//!   再次上传同一文件（同 server/dir/name/size/mtime/sha256）时先 `GET .../{uploadId}`
//!   查询服务端已收到的分片，只补传缺失分片；服务端会话 404 时重新 init；
//!   成功 / SHA 不符时删除状态；createdAt 超过 24h 的状态会被清理。
//!
//! 纯状态机（分片计算、状态文件读写/匹配/清理）与网络编排分离，前者有单元测试。

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use tokio::io::{AsyncReadExt, AsyncSeekExt, SeekFrom};

use crate::api::{self, Api, FileMeta};

/// 超过该大小走分片上传；小于等于走单次 multipart（32MB）。
pub const MULTIPART_THRESHOLD: u64 = 32 * 1024 * 1024;

/// 单个分片最多尝试次数：首次 + 最多 3 次重试。
pub const MAX_CHUNK_ATTEMPTS: u32 = 4;

/// 断点续传状态文件（位于 app config dir）。
pub const UPLOAD_STATE_FILE_NAME: &str = "uploads.json";

/// 上传状态有效期：24 小时。
pub const STATE_TTL_MILLIS: i64 = 24 * 60 * 60 * 1000;

/// 续传记录：标识一次「某服务器 + 目录 + 文件」的分片上传会话。
///
/// `dir` 为空串表示存储根目录；`mtime` / `createdAt` 均为 Unix 毫秒时间戳。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadRecord {
    pub server_id: String,
    /// 存储根 id；`None` = 服务端默认根（task-16 多存储根）。
    #[serde(default)]
    pub root_id: Option<String>,
    #[serde(default)]
    pub dir: String,
    pub file_name: String,
    pub size: u64,
    pub mtime: i64,
    pub upload_id: String,
    #[serde(default)]
    pub sha256: String,
    pub created_at: i64,
}

impl UploadRecord {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        server_id: &str,
        root_id: Option<&str>,
        dir: Option<&str>,
        file_name: &str,
        size: u64,
        mtime: i64,
        upload_id: &str,
        sha256: &str,
    ) -> Self {
        Self {
            server_id: server_id.to_string(),
            root_id: normalize_root_id(root_id),
            dir: normalize_dir(dir),
            file_name: file_name.to_string(),
            size,
            mtime,
            upload_id: upload_id.to_string(),
            sha256: sha256.to_string(),
            created_at: now_millis(),
        }
    }

    /// 测试辅助：指定 createdAt。
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn with_created_at(mut self, created_at: i64) -> Self {
        self.created_at = created_at;
        self
    }

    /// 是否与本次上传属于同一个文件 / 同一目标（不含 uploadId）。
    pub fn matches(
        &self,
        server_id: &str,
        root_id: Option<&str>,
        dir: Option<&str>,
        file_name: &str,
        size: u64,
        mtime: i64,
        sha256: &str,
    ) -> bool {
        self.server_id == server_id
            && self.root_id == normalize_root_id(root_id)
            && self.dir == normalize_dir(dir)
            && self.file_name == file_name
            && self.size == size
            && self.mtime == mtime
            && self.sha256 == sha256
    }

    fn is_fresh(&self, now: i64) -> bool {
        // 兼容时钟回拨 / 未来时间戳：只要不早于 now - TTL 就视为有效。
        now.saturating_sub(self.created_at) <= STATE_TTL_MILLIS
    }
}

/// `uploads.json` 的读写与查询。写文件时先写临时文件再原子重命名。
#[derive(Debug, Clone)]
pub struct UploadStore {
    path: PathBuf,
    records: Vec<UploadRecord>,
}

impl UploadStore {
    /// 读取状态文件；文件不存在或内容损坏时返回空集合（不阻断上传）。
    pub fn load(path: &Path) -> Result<Self, String> {
        let records = if path.exists() {
            let content = std::fs::read_to_string(path)
                .map_err(|e| format!("读取续传状态失败：{e}"))?;
            if content.trim().is_empty() {
                Vec::new()
            } else {
                match serde_json::from_str::<Vec<UploadRecord>>(&content) {
                    Ok(records) => records,
                    Err(err) => {
                        eprintln!("续传状态文件损坏，已忽略：{err}");
                        Vec::new()
                    }
                }
            }
        } else {
            Vec::new()
        };
        Ok(Self {
            path: path.to_path_buf(),
            records,
        })
    }

    /// 原子落盘（临时文件 + rename）。
    pub fn save(&self) -> Result<(), String> {
        let json = serde_json::to_string_pretty(&self.records)
            .map_err(|e| format!("续传状态序列化失败：{e}"))?;
        if let Some(parent) = self.path.parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent)
                    .map_err(|e| format!("创建续传状态目录失败：{e}"))?;
            }
        }
        let temp = self.path.with_extension("json.tmp");
        std::fs::write(&temp, json).map_err(|e| format!("写入续传状态失败：{e}"))?;
        std::fs::rename(&temp, &self.path).map_err(|e| format!("保存续传状态失败：{e}"))?;
        Ok(())
    }

    #[cfg_attr(not(test), allow(dead_code))]
    pub fn records(&self) -> &[UploadRecord] {
        &self.records
    }

    #[cfg_attr(not(test), allow(dead_code))]
    pub fn is_empty(&self) -> bool {
        self.records.is_empty()
    }

    /// 找到与本次上传匹配、且未过期的续传记录。
    #[allow(clippy::too_many_arguments)]
    pub fn find(
        &self,
        server_id: &str,
        root_id: Option<&str>,
        dir: Option<&str>,
        file_name: &str,
        size: u64,
        mtime: i64,
        sha256: &str,
    ) -> Option<&UploadRecord> {
        let now = now_millis();
        self.records.iter().find(|record| {
            record.matches(server_id, root_id, dir, file_name, size, mtime, sha256)
                && record.is_fresh(now)
        })
    }

    /// 写入/替换同一文件（同 server/dir/name/size/mtime）的记录。
    pub fn upsert(&mut self, record: UploadRecord) {
        self.records.retain(|existing| {
            !(existing.server_id == record.server_id
                && existing.root_id == record.root_id
                && existing.dir == record.dir
                && existing.file_name == record.file_name
                && existing.size == record.size
                && existing.mtime == record.mtime)
        });
        self.records.push(record);
    }

    /// 删除与本次上传对应的记录。
    #[allow(clippy::too_many_arguments)]
    pub fn remove(
        &mut self,
        server_id: &str,
        root_id: Option<&str>,
        dir: Option<&str>,
        file_name: &str,
        size: u64,
        mtime: i64,
    ) -> usize {
        let root_id = normalize_root_id(root_id);
        let dir = normalize_dir(dir);
        let before = self.records.len();
        self.records.retain(|record| {
            !(record.server_id == server_id
                && record.root_id == root_id
                && record.dir == dir
                && record.file_name == file_name
                && record.size == size
                && record.mtime == mtime)
        });
        before - self.records.len()
    }

    /// 按 uploadId 删除记录。
    pub fn remove_by_upload_id(&mut self, upload_id: &str) -> usize {
        let before = self.records.len();
        self.records.retain(|record| record.upload_id != upload_id);
        before - self.records.len()
    }

    /// 删除某台服务器的全部续传记录（删除服务器时清理）。
    pub fn remove_by_server_id(&mut self, server_id: &str) -> usize {
        let before = self.records.len();
        self.records.retain(|record| record.server_id != server_id);
        before - self.records.len()
    }

    /// 清理超过 24h 的记录，返回清理数量。
    pub fn cleanup_stale(&mut self, now: i64) -> usize {
        let before = self.records.len();
        self.records.retain(|record| record.is_fresh(now));
        before - self.records.len()
    }
}

/// 归一化存储根 id：`None` / 空串都表示服务端默认根，存为 None。
pub fn normalize_root_id(root_id: Option<&str>) -> Option<String> {
    root_id
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(|value| value.to_string())
}

/// 归一化目录：`None` / 空串都表示根目录，存为空串。
pub fn normalize_dir(dir: Option<&str>) -> String {
    dir.map(str::trim)
        .unwrap_or_default()
        .trim_matches('/')
        .to_string()
}

/// 是否走分片上传。0 字节文件在更早的阶段就被拒绝。
pub fn should_use_chunked(size: u64) -> bool {
    size > MULTIPART_THRESHOLD
}

/// 分片总数。
pub fn chunk_count(size: u64, chunk_size: u64) -> u32 {
    if size == 0 || chunk_size == 0 {
        return 0;
    }
    size.div_ceil(chunk_size) as u32
}

/// 第 `index` 片的字节数（最后一片可能更小）。
pub fn chunk_len(size: u64, chunk_size: u64, index: u32) -> u64 {
    if chunk_size == 0 {
        return 0;
    }
    let offset = index as u64 * chunk_size;
    if offset >= size {
        0
    } else {
        chunk_size.min(size - offset)
    }
}

/// 服务端尚未收到的分片索引（升序）；非法/重复索引被忽略。
pub fn missing_chunks(total_chunks: u32, received: &[u32]) -> Vec<u32> {
    let mut received = received.to_vec();
    received.sort_unstable();
    received.dedup();
    (0..total_chunks)
        .filter(|index| received.binary_search(index).is_err())
        .collect()
}

/// 已接收分片的累计字节数（用于续传初始进度）。
pub fn received_bytes(size: u64, chunk_size: u64, received: &[u32]) -> u64 {
    let total_chunks = chunk_count(size, chunk_size);
    let mut seen = received.to_vec();
    seen.sort_unstable();
    seen.dedup();
    seen.iter()
        .filter(|index| **index < total_chunks)
        .map(|index| chunk_len(size, chunk_size, *index))
        .sum()
}

/// 读取文件修改时间（Unix 毫秒）。
pub fn file_mtime_millis(path: &Path) -> Result<i64, String> {
    let metadata = std::fs::metadata(path).map_err(|e| format!("无法读取文件信息：{e}"))?;
    let modified = metadata
        .modified()
        .map_err(|e| format!("无法读取文件修改时间：{e}"))?;
    let millis = modified
        .duration_since(UNIX_EPOCH)
        .map_err(|e| format!("文件修改时间异常：{e}"))?
        .as_millis() as i64;
    Ok(millis)
}

pub(crate) fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as i64)
        .unwrap_or_default()
}

fn local_file_name(path: &Path) -> String {
    path.file_name()
        .map(|name| name.to_string_lossy().to_string())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| "file".to_string())
}

/// 上传一个本地文件（自动选择单次 multipart 或分片续传），进度按累计已发送字节回调。
///
/// `upload_id` 仅用于前端事件关联（不参与续传匹配）。
pub async fn upload_with_resume<F>(
    api: &Api,
    state_path: &Path,
    path: &str,
    description: Option<&str>,
    dir: Option<&str>,
    root: Option<&str>,
    _upload_id: &str,
    on_progress: F,
) -> Result<FileMeta, String>
where
    F: Fn(u64, u64) + Send + Sync + 'static,
{
    let source = PathBuf::from(path);
    let metadata = tokio::fs::metadata(&source)
        .await
        .map_err(|e| format!("无法读取所选文件：{e}"))?;
    if !metadata.is_file() {
        return Err("所选路径不是文件".to_string());
    }
    let total = metadata.len();
    if total == 0 {
        return Err("不能上传空文件".to_string());
    }

    let progress = Arc::new(on_progress);
    if !should_use_chunked(total) {
        let progress = Arc::clone(&progress);
        return api
            .upload_file_multipart(path, description, dir, root, move |sent, total| {
                progress(sent, total)
            })
            .await;
    }

    let file_name = local_file_name(&source);
    let dir_label = dir
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .unwrap_or("根目录");
    let root_label = normalize_root_id(root).unwrap_or_else(|| "defaultRoot".to_string());

    let description = description.map(str::trim).filter(|value| !value.is_empty());
    if let Some(description) = description {
        if description.chars().count() > 200 {
            return Err("说明不能超过 200 个字符".to_string());
        }
    }

    // 分片上传同样先算整文件 SHA-256（流式，不占内存）。
    let sha256 = api::sha256_file(&source).await?;
    let mtime = file_mtime_millis(&source)?;
    let server_id = api.server().id.clone();

    let mut store = UploadStore::load(state_path)?;
    if store.cleanup_stale(now_millis()) > 0 {
        let _ = store.save();
    }

    // ---------- 尝试复用上次未完成的分片会话 ----------
    let mut session: Option<(String, u64, Vec<u32>)> = None;
    if let Some(record) = store
        .find(&server_id, root, dir, &file_name, total, mtime, &sha256)
        .cloned()
    {
        match api.chunk_status(&record.upload_id).await {
            Ok(status) => {
                let chunk_size = status.effective_chunk_size();
                if status.upload_id == record.upload_id
                    && status.name == file_name
                    && status.size == total
                {
                    eprintln!(
                        "断点续传：复用会话 {}（root={}，已收到 {} 片，目标「{}」）",
                        record.upload_id,
                        root_label,
                        status.received.len(),
                        dir_label
                    );
                    session = Some((record.upload_id.clone(), chunk_size, status.received.clone()));
                } else {
                    // 服务端会话信息与本地文件不一致：丢弃旧会话重新开始。
                    let _ = api.cancel_chunked_upload(&record.upload_id).await;
                    store.remove(&server_id, root, dir, &file_name, total, mtime);
                }
            }
            Err(err) if api::is_session_not_found_error(&err) => {
                // 服务端已清理会话（例如超过 24h），重新 init。
                store.remove(&server_id, root, dir, &file_name, total, mtime);
            }
            Err(err) => {
                return Err(format!("查询续传状态失败：{err}"));
            }
        }
    }

    let (mut session_id, mut chunk_size, received) = match session {
        Some(session) => session,
        None => {
            let init = api
                .init_chunked_upload(&file_name, dir, total, &sha256, description, root)
                .await?;
            let chunk_size = init.effective_chunk_size();
            store.upsert(UploadRecord::new(
                &server_id,
                root,
                dir,
                &file_name,
                total,
                mtime,
                &init.upload_id,
                &sha256,
            ));
            store.save()?;
            (init.upload_id, chunk_size, Vec::new())
        }
    };

    // ---------- 补传缺失分片 ----------
    let initial_sent = received_bytes(total, chunk_size, &received);
    progress(initial_sent, total);
    upload_missing_chunks(
        api,
        &source,
        &session_id,
        chunk_size,
        total,
        &received,
        &*progress,
        initial_sent,
    )
    .await?;

    // ---------- 合并 ----------
    let mut fresh_retry = false;
    loop {
        match api.complete_chunked_upload(&session_id).await {
            Ok(meta) => {
                store.remove_by_upload_id(&session_id);
                let _ = store.save();
                progress(total, total);
                return Ok(meta);
            }
            Err(err) if api::is_content_mismatch_error(&err) => {
                // 服务端已清理会话，本地状态也删除。
                store.remove_by_upload_id(&session_id);
                let _ = store.save();
                return Err(format!("文件内容校验失败（服务器已清理上传会话）：{err}"));
            }
            Err(err) if api::is_session_not_found_error(&err) && !fresh_retry => {
                // 会话在合并前丢失：重新 init 并全量重传一次。
                fresh_retry = true;
                eprintln!("上传会话 {} 已丢失，重新创建会话并重传", session_id);
                store.remove_by_upload_id(&session_id);
                let init = api
                    .init_chunked_upload(&file_name, dir, total, &sha256, description, root)
                    .await?;
                session_id = init.upload_id.clone();
                chunk_size = init.effective_chunk_size();
                store.upsert(UploadRecord::new(
                    &server_id,
                    root,
                    dir,
                    &file_name,
                    total,
                    mtime,
                    &session_id,
                    &sha256,
                ));
                let _ = store.save();
                upload_missing_chunks(
                    api,
                    &source,
                    &session_id,
                    chunk_size,
                    total,
                    &[],
                    &*progress,
                    0,
                )
                .await?;
                continue;
            }
            Err(err) if api::is_too_large_error(&err) => {
                store.remove_by_upload_id(&session_id);
                let _ = store.save();
                return Err(format!("文件超过服务端大小限制：{err}"));
            }
            Err(err) => {
                // 缺片 / 大小不符等：服务端保留会话，本地保留状态，用户重试即可续传。
                let _ = store.save();
                return Err(format!("合并分片失败（会话已保留，可重试续传）：{err}"));
            }
        }
    }
}

/// 补传缺失分片，返回累计已发送字节数。每片失败最多重试 3 次（网络/5xx/限流类错误）。
#[allow(clippy::too_many_arguments)]
async fn upload_missing_chunks<F>(
    api: &Api,
    source: &Path,
    session_id: &str,
    chunk_size: u64,
    total: u64,
    received: &[u32],
    progress: &F,
    mut sent: u64,
) -> Result<u64, String>
where
    F: Fn(u64, u64) + Send + Sync + ?Sized,
{
    let total_chunks = chunk_count(total, chunk_size);
    let missing = missing_chunks(total_chunks, received);
    if missing.is_empty() {
        return Ok(sent);
    }

    let mut file = tokio::fs::File::open(source)
        .await
        .map_err(|e| format!("无法打开所选文件：{e}"))?;

    for index in missing {
        let offset = index as u64 * chunk_size;
        let length = chunk_len(total, chunk_size, index);
        if length == 0 {
            return Err(format!("分片 {index} 长度异常，文件可能已被修改"));
        }
        file.seek(SeekFrom::Start(offset))
            .await
            .map_err(|e| format!("读取文件失败（分片 {index}）：{e}"))?;
        let mut buffer = vec![0u8; length as usize];
        file.read_exact(&mut buffer)
            .await
            .map_err(|e| format!("读取文件失败（分片 {index}）：{e}"))?;

        let mut last_error: Option<String> = None;
        let mut uploaded = false;
        for attempt in 1..=MAX_CHUNK_ATTEMPTS {
            match api.put_chunk(session_id, index, &buffer).await {
                Ok(()) => {
                    uploaded = true;
                    break;
                }
                Err(err) => {
                    let retryable = api::is_retryable_error(&err);
                    if retryable && attempt < MAX_CHUNK_ATTEMPTS {
                        eprintln!(
                            "分片 {index} 上传失败（第 {attempt} 次尝试，将重试）：{err}"
                        );
                        last_error = Some(err);
                        tokio::time::sleep(Duration::from_millis(300 * attempt as u64)).await;
                        continue;
                    }
                    return Err(if retryable {
                        format!(
                            "分片 {} 上传失败（已重试 {} 次）：{}",
                            index,
                            MAX_CHUNK_ATTEMPTS - 1,
                            last_error.unwrap_or(err)
                        )
                    } else {
                        format!("分片 {} 上传失败：{}", index, err)
                    });
                }
            }
        }
        if !uploaded {
            return Err(format!(
                "分片 {} 上传失败：{}",
                index,
                last_error.unwrap_or_else(|| "未知错误".to_string())
            ));
        }
        sent = (sent + length).min(total);
        progress(sent, total);
    }

    Ok(sent)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "secure-upload-uploads-test-{tag}-{}",
            uuid::Uuid::new_v4()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn record(tag: &str) -> UploadRecord {
        UploadRecord::new(
            "server-1",
            None,
            Some("docs"),
            &format!("{tag}.bin"),
            100,
            1_760_000_000_000,
            &format!("upload-{tag}"),
            "abc123",
        )
    }

    fn record_in_root(tag: &str, root: &str) -> UploadRecord {
        UploadRecord::new(
            "server-1",
            Some(root),
            Some("docs"),
            &format!("{tag}.bin"),
            100,
            1_760_000_000_000,
            &format!("upload-{tag}"),
            "abc123",
        )
    }

    #[test]
    fn chunk_math_is_consistent() {
        let size = 20 * 1024 * 1024 + 7;
        let chunk = 8 * 1024 * 1024;
        let total = chunk_count(size, chunk);
        assert_eq!(total, 3);
        assert_eq!(chunk_len(size, chunk, 0), chunk);
        assert_eq!(chunk_len(size, chunk, 1), chunk);
        assert_eq!(chunk_len(size, chunk, 2), 20 * 1024 * 1024 + 7 - 2 * chunk);
        assert_eq!(chunk_len(size, chunk, 3), 0);
        let sum: u64 = (0..total).map(|i| chunk_len(size, chunk, i)).sum();
        assert_eq!(sum, size);

        assert_eq!(chunk_count(8 * 1024 * 1024, chunk), 1);
        assert_eq!(chunk_count(8 * 1024 * 1024 + 1, chunk), 2);
        assert_eq!(chunk_count(0, chunk), 0);
        assert_eq!(chunk_count(100, 0), 0);
    }

    #[test]
    fn missing_chunks_and_received_bytes() {
        let size = 20 * 1024 * 1024;
        let chunk = 8 * 1024 * 1024;
        let total = chunk_count(size, chunk);
        assert_eq!(missing_chunks(total, &[0, 2]), vec![1]);
        assert_eq!(missing_chunks(total, &[]), vec![0, 1, 2]);
        assert_eq!(missing_chunks(total, &[0, 1, 2]), Vec::<u32>::new());
        // 非法/重复索引被忽略
        assert_eq!(missing_chunks(total, &[1, 1, 99]), vec![0, 2]);

        assert_eq!(received_bytes(size, chunk, &[0]), chunk);
        assert_eq!(received_bytes(size, chunk, &[0, 2]), chunk + (size - 2 * chunk));
        assert_eq!(received_bytes(size, chunk, &[99]), 0);
    }

    #[test]
    fn threshold_selection() {
        assert!(!should_use_chunked(0));
        assert!(!should_use_chunked(1));
        assert!(!should_use_chunked(MULTIPART_THRESHOLD));
        assert!(should_use_chunked(MULTIPART_THRESHOLD + 1));
    }

    #[test]
    fn store_roundtrip_and_match_rules() {
        let dir = temp_dir("store");
        let path = dir.join(UPLOAD_STATE_FILE_NAME);

        let mut store = UploadStore::load(&path).unwrap();
        assert!(store.is_empty());

        store.upsert(record("a"));
        store.upsert(record("b"));
        assert_eq!(store.records().len(), 2);
        store.save().unwrap();
        assert!(path.exists());

        // JSON 使用 camelCase，且不含任何凭据
        let content = std::fs::read_to_string(&path).unwrap();
        assert!(content.contains("\"serverId\""));
        assert!(content.contains("\"rootId\""));
        assert!(content.contains("\"fileName\""));
        assert!(content.contains("\"uploadId\""));
        assert!(content.contains("\"createdAt\""));
        assert!(!content.contains("appSecret"));

        let store = UploadStore::load(&path).unwrap();
        let found = store
            .find(
                "server-1",
                None,
                Some("docs"),
                "a.bin",
                100,
                1_760_000_000_000,
                "abc123",
            )
            .expect("应命中续传记录");
        assert_eq!(found.upload_id, "upload-a");

        // 不同 server / dir / name / size / mtime / sha 都不匹配
        assert!(store
            .find("server-2", None, Some("docs"), "a.bin", 100, 1_760_000_000_000, "abc123")
            .is_none());
        assert!(store
            .find("server-1", None, Some("other"), "a.bin", 100, 1_760_000_000_000, "abc123")
            .is_none());
        assert!(store
            .find("server-1", None, Some("docs"), "a.bin", 101, 1_760_000_000_000, "abc123")
            .is_none());
        assert!(store
            .find("server-1", None, Some("docs"), "a.bin", 100, 1_760_000_000_001, "abc123")
            .is_none());
        assert!(store
            .find("server-1", None, Some("docs"), "a.bin", 100, 1_760_000_000_000, "different")
            .is_none());

        // 根目录：None 与 Some("") 等价
        let mut root_record = record("root");
        root_record.dir = String::new();
        store_upsert(&path, root_record);
        let store = UploadStore::load(&path).unwrap();
        assert!(store
            .find(
                "server-1",
                None,
                None,
                "root.bin",
                100,
                1_760_000_000_000,
                "abc123"
            )
            .is_some());
        assert!(store
            .find(
                "server-1",
                None,
                Some(""),
                "root.bin",
                100,
                1_760_000_000_000,
                "abc123"
            )
            .is_some());

        let _ = std::fs::remove_dir_all(&dir);
    }

    fn store_upsert(path: &Path, record: UploadRecord) {
        let mut store = UploadStore::load(path).unwrap();
        store.upsert(record);
        store.save().unwrap();
    }

    #[test]
    fn upsert_replaces_same_file_and_remove_works() {
        let dir = temp_dir("upsert");
        let path = dir.join(UPLOAD_STATE_FILE_NAME);
        let mut store = UploadStore::load(&path).unwrap();

        let mut first = record("a");
        store.upsert(first.clone());
        first.upload_id = "upload-a-2".to_string();
        store.upsert(first);
        assert_eq!(store.records().len(), 1, "同一文件应替换而非追加");
        assert_eq!(store.records()[0].upload_id, "upload-a-2");

        assert_eq!(store.remove("server-1", None, Some("docs"), "a.bin", 100, 1_760_000_000_000), 1);
        assert!(store.is_empty());
        assert_eq!(
            store.remove("server-1", None, Some("docs"), "missing.bin", 100, 1_760_000_000_000),
            0
        );

        store.upsert(record("c"));
        assert_eq!(store.remove_by_upload_id("upload-c"), 1);
        assert!(store.is_empty());

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn stale_records_are_cleaned_after_24h() {
        let dir = temp_dir("stale");
        let path = dir.join(UPLOAD_STATE_FILE_NAME);
        let mut store = UploadStore::load(&path).unwrap();
        let now = now_millis();

        store.upsert(record("fresh").with_created_at(now));
        store.upsert(record("stale").with_created_at(now - STATE_TTL_MILLIS - 1));
        assert_eq!(store.records().len(), 2);

        assert_eq!(store.cleanup_stale(now), 1);
        assert_eq!(store.records().len(), 1);
        assert_eq!(store.records()[0].file_name, "fresh.bin");

        // 过期记录不能被 find 命中（即使尚未被清理）
        let mut store = UploadStore::load(&path).unwrap();
        store.upsert(record("stale2").with_created_at(now - STATE_TTL_MILLIS - 1000));
        let store = UploadStore::load(&path).unwrap();
        assert!(store
            .find(
                "server-1",
                None,
                Some("docs"),
                "stale2.bin",
                100,
                1_760_000_000_000,
                "abc123"
            )
            .is_none());

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn corrupt_state_file_is_ignored() {
        let dir = temp_dir("corrupt");
        let path = dir.join(UPLOAD_STATE_FILE_NAME);
        std::fs::write(&path, b"{ not valid json").unwrap();
        let store = UploadStore::load(&path).unwrap();
        assert!(store.is_empty(), "损坏的状态文件应被忽略而不是阻断上传");

        std::fs::write(&path, b"").unwrap();
        assert!(UploadStore::load(&path).unwrap().is_empty());

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn dir_normalization_and_mtime() {
        assert_eq!(normalize_dir(None), "");
        assert_eq!(normalize_dir(Some("")), "");
        assert_eq!(normalize_dir(Some(" /docs/2026/ ")), "docs/2026");
        assert_eq!(normalize_dir(Some("docs/2026")), "docs/2026");

        let dir = temp_dir("mtime");
        let file = dir.join("f.bin");
        std::fs::write(&file, b"hello").unwrap();
        let mtime = file_mtime_millis(&file).unwrap();
        assert!(mtime > 0);
        assert!(file_mtime_millis(&dir.join("missing")).is_err());

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn resume_keys_include_root_id() {
        let dir = temp_dir("root");
        let path = dir.join(UPLOAD_STATE_FILE_NAME);

        let mut store = UploadStore::load(&path).unwrap();
        store.upsert(record_in_root("a", "work"));
        store.upsert(record_in_root("b", "media"));
        store.save().unwrap();

        let content = std::fs::read_to_string(&path).unwrap();
        assert!(content.contains("\"rootId\": \"work\""));
        assert!(content.contains("\"rootId\": \"media\""));

        let mut store = UploadStore::load(&path).unwrap();
        // 同 server/dir/name/size/mtime，不同 root 必须各自命中自己的记录。
        let work = store
            .find("server-1", Some("work"), Some("docs"), "a.bin", 100, 1_760_000_000_000, "abc123")
            .expect("root=work 应命中");
        assert_eq!(work.upload_id, "upload-a");
        assert!(
            store
                .find("server-1", Some("media"), Some("docs"), "a.bin", 100, 1_760_000_000_000, "abc123")
                .is_none(),
            "root=media 不应命中 root=work 的记录"
        );
        // 不传 root（默认根）也不应命中明确指定了 root 的记录。
        assert!(store
            .find("server-1", None, Some("docs"), "a.bin", 100, 1_760_000_000_000, "abc123")
            .is_none());

        // 删除也必须带 root，不得误删其它根的记录。
        assert_eq!(
            store.remove("server-1", Some("work"), Some("docs"), "a.bin", 100, 1_760_000_000_000),
            1
        );
        assert_eq!(store.records().len(), 1);
        assert_eq!(store.records()[0].root_id.as_deref(), Some("media"));

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn save_is_atomic_and_reloads() {
        let dir = temp_dir("atomic");
        let path = dir.join(UPLOAD_STATE_FILE_NAME);
        let mut store = UploadStore::load(&path).unwrap();
        for index in 0..5 {
            let mut record = record(&format!("r{index}"));
            record.size = index as u64;
            store.upsert(record);
        }
        store.save().unwrap();
        assert!(!path.with_extension("json.tmp").exists(), "临时文件应被重命名");

        let store = UploadStore::load(&path).unwrap();
        assert_eq!(store.records().len(), 5);

        let _ = std::fs::remove_dir_all(&dir);
    }
}
