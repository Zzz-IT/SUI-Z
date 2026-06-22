use libc::{c_char, c_int};
use std::ffi::CStr;
use std::panic::{catch_unwind, AssertUnwindSafe};

unsafe fn cstr_to_str<'a>(ptr: *const c_char) -> Option<&'a str> {
    if ptr.is_null() {
        return None;
    }

    let cstr = unsafe {
        // Safety:
        // The caller must pass a valid, readable, NUL-terminated C string.
        CStr::from_ptr(ptr)
    };

    cstr.to_str().ok()
}

/// Validate a shell directory name from C/C++.
///
/// # Safety
///
/// `name` must be either null or a valid, readable, NUL-terminated C string.
/// The pointer is not retained after this call returns.
#[no_mangle]
pub unsafe extern "C" fn suiz_validate_shell_dir_name(name: *const c_char) -> c_int {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let Some(name) = (unsafe { cstr_to_str(name) }) else {
            return 0;
        };

        if suiz_core::shell_dir::is_valid_shell_dir_name(name) {
            1
        } else {
            0
        }
    }));

    result.unwrap_or(0)
}

/// Trim a marker string and validate it as a shell directory name from C/C++.
///
/// # Safety
///
/// `marker` must be either null or a valid, readable, NUL-terminated C string.
/// The pointer is not retained after this call returns.
#[no_mangle]
pub unsafe extern "C" fn suiz_trim_marker_is_valid(marker: *const c_char) -> c_int {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let Some(marker) = (unsafe { cstr_to_str(marker) }) else {
            return 0;
        };

        let marker = suiz_core::shell_dir::trim_marker(marker);
        if suiz_core::shell_dir::is_valid_shell_dir_name(marker) {
            1
        } else {
            0
        }
    }));

    result.unwrap_or(0)
}
