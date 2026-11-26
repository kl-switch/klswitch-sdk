//! Phase 7 — explicit WASM memory ownership for the JSON in/out boundary.
//!
//! Two buffer kinds cross the boundary:
//! - **input buffers**: the host calls [`ks_alloc`], writes a payload, passes
//!   `(ptr, len)` to an export, and frees them with [`ks_free`] after the call.
//! - **result buffers**: an export returns a pointer to `[len: u32 LE][json]`;
//!   the host reads the 4-byte length, copies the JSON, then calls
//!   [`ks_free_result`] (which reads the prefix to size the free).
//!
//! All buffers use byte alignment (1); `dealloc` always uses the same layout as
//! the matching `alloc`. See `docs/wasm-host-contract.md` for the host protocol.

use std::alloc::{alloc as raw_alloc, dealloc, Layout};

/// Length-prefix size for result buffers (a little-endian `u32`).
pub(crate) const LEN_PREFIX: usize = 4;

/// Allocate `size` bytes for the host to write an input payload into.
/// Returns null for a zero-size request.
#[no_mangle]
pub extern "C" fn ks_alloc(size: usize) -> *mut u8 {
    if size == 0 {
        return std::ptr::null_mut();
    }
    let layout = Layout::from_size_align(size, 1).expect("byte layout");
    // SAFETY: size > 0 and align 1 form a valid non-zero layout.
    unsafe { raw_alloc(layout) }
}

/// Free an input buffer previously returned by [`ks_alloc`].
///
/// # Safety
/// `ptr` must have come from [`ks_alloc`] with the same `size`, and must not be
/// used afterward (host contract).
#[no_mangle]
pub unsafe extern "C" fn ks_free(ptr: *mut u8, size: usize) {
    if ptr.is_null() || size == 0 {
        return;
    }
    let layout = Layout::from_size_align(size, 1).expect("byte layout");
    dealloc(ptr, layout);
}

/// Move `json` into a freshly allocated result buffer laid out as
/// `[len: u32 LE][json bytes]` and return a pointer to it. The host reads the
/// length from the first [`LEN_PREFIX`] bytes, copies the JSON, then frees the
/// buffer with [`ks_free_result`].
pub(crate) fn into_result(json: &[u8]) -> *mut u8 {
    // The length prefix is a u32. On wasm32 `usize` is `u32` so this never trips;
    // the hard check prevents a truncated prefix (which would make `ks_free_result`
    // reconstruct a mismatched layout) if a >4 GiB result were ever produced on a
    // 64-bit host.
    let len = u32::try_from(json.len()).expect("result exceeds u32 length prefix");
    let total = LEN_PREFIX + json.len();
    let layout = Layout::from_size_align(total, 1).expect("byte layout");
    // SAFETY: total >= LEN_PREFIX > 0, so the layout is valid and non-zero.
    unsafe {
        let ptr = raw_alloc(layout);
        assert!(!ptr.is_null(), "result buffer allocation failed");
        let len_prefix = len.to_le_bytes();
        std::ptr::copy_nonoverlapping(len_prefix.as_ptr(), ptr, LEN_PREFIX);
        std::ptr::copy_nonoverlapping(json.as_ptr(), ptr.add(LEN_PREFIX), json.len());
        ptr
    }
}

/// Free a result buffer returned by an export. Reads the length prefix to
/// determine the total allocation size.
///
/// # Safety
/// `ptr` must be a result buffer produced by an export (length-prefixed layout)
/// and must not be used afterward (host contract).
#[no_mangle]
pub unsafe extern "C" fn ks_free_result(ptr: *mut u8) {
    if ptr.is_null() {
        return;
    }
    let mut len_prefix = [0u8; LEN_PREFIX];
    std::ptr::copy_nonoverlapping(ptr, len_prefix.as_mut_ptr(), LEN_PREFIX);
    let total = LEN_PREFIX + u32::from_le_bytes(len_prefix) as usize;
    let layout = Layout::from_size_align(total, 1).expect("byte layout");
    dealloc(ptr, layout);
}
