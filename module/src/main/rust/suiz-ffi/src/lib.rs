use libc::{c_char, c_int};
use std::ffi::CStr;
use std::panic::{catch_unwind, AssertUnwindSafe};

fn cstr_to_str<'a>(ptr: *const c_char) -> Option<&'a str> {
    if ptr.is_null() {
        return None;
    }

    let cstr = unsafe {
        // Safety:
        // The caller must pass a valid NUL-terminated C string.
        CStr::from_ptr(ptr)
    };

    cstr.to_str().ok()
}

#[no_mangle]
pub extern "C" fn suiz_validate_shell_dir_name(name: *const c_char) -> c_int {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let Some(name) = cstr_to_str(name) else {
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

#[no_mangle]
pub extern "C" fn suiz_trim_marker_is_valid(marker: *const c_char) -> c_int {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let Some(marker) = cstr_to_str(marker) else {
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
