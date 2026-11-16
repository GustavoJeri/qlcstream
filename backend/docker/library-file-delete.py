"""Delete one regular library file using pinned directories; never follow links.

Exit codes: 0 completed/already absent, 2 invalid path/link, 3 IO failure.
No filesystem paths, input, or OS exception messages are printed.
"""
import os
import stat
import sys


def relative_parts(value):
    parts = value.split("/")
    drive = len(value) > 1 and value[0] in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ" and value[1] == ":"
    if not value or value.startswith(("/", "\\")) or drive:
        raise ValueError()
    if any(part in ("", ".", "..") for part in parts):
        raise ValueError()
    return parts


def operate(root, relative, delete):
    parts = relative_parts(relative)
    if not root.startswith("/") or any(part in (".", "..") for part in root.split("/")):
        raise ValueError()
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    handles = [os.open("/", flags)]
    try:
        for part in (part for part in root.split("/") if part):
            handles.append(os.open(part, flags, dir_fd=handles[-1]))
        for part in parts[:-1]:
            try:
                handles.append(os.open(part, flags, dir_fd=handles[-1]))
            except FileNotFoundError:
                return  # A missing ordinary parent has no leaf to remove.
        try:
            info = os.stat(parts[-1], dir_fd=handles[-1], follow_symlinks=False)
        except FileNotFoundError:
            return
        if not stat.S_ISREG(info.st_mode):
            raise ValueError()
        if delete:
            try:
                os.unlink(parts[-1], dir_fd=handles[-1])
            except FileNotFoundError:
                pass
    finally:
        for handle in reversed(handles):
            os.close(handle)


if __name__ == "__main__":
    try:
        if len(sys.argv) != 4 or sys.argv[1] not in ("check", "delete"):
            raise ValueError()
        operate(sys.argv[2], sys.argv[3], sys.argv[1] == "delete")
    except (ValueError, NotADirectoryError):
        sys.exit(2)
    except OSError:
        sys.exit(3)
