#include <errno.h>
#include <stddef.h>
#include <string.h>

comptime import "stdlib:/strings/string.cp";

@test "string init and empty invariants" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.size() == 0);
    @assert(value.capacity_of() == 0);
    @assert(value.empty());
    @assert(strcmp(value.c_str(), "") == 0);
    @assert(value.strlen() == 0);
    value.destroy();
}

@test "string assign reserve clear destroy" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("hello") == STRING_OK);
    @assert(value.size() == 5);
    @assert(strcmp(value.c_str(), "hello") == 0);
    @assert(value.capacity_of() >= 5);
    size_t old_capacity = value.capacity_of();
    @assert(value.reserve(old_capacity + 20) == STRING_OK);
    @assert(value.capacity_of() >= old_capacity + 20);
    @assert(strcmp(value.c_str(), "hello") == 0);
    value.clear();
    @assert(value.empty());
    @assert(strcmp(value.c_str(), "") == 0);
    value.destroy();
    @assert(value.data == NULL);
    @assert(value.size() == 0);
    @assert(value.capacity_of() == 0);
}

@test "string assign n truncates and terminates" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign_n("abcdef", 3) == STRING_OK);
    @assert(value.size() == 3);
    @assert(strcmp(value.c_str(), "abc") == 0);
    @assert(value.assign_n("xy", 99) == STRING_OK);
    @assert(value.size() == 2);
    @assert(strcmp(value.c_str(), "xy") == 0);
    value.destroy();
}

@test "string append variants" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("ab") == STRING_OK);
    @assert(value.append("cd") == STRING_OK);
    @assert(value.append_n("EFGH", 2) == STRING_OK);
    @assert(value.append_char('!') == STRING_OK);
    @assert(strcmp(value.c_str(), "abcdEF!") == 0);
    @assert(value.size() == 7);
    value.destroy();
}

@test "string strcpy strncpy strcat strncat facade" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.strcpy("alpha") == STRING_OK);
    @assert(strcmp(value.c_str(), "alpha") == 0);
    @assert(value.strcat("-beta") == STRING_OK);
    @assert(strcmp(value.c_str(), "alpha-beta") == 0);
    @assert(value.strncpy("123456", 4) == STRING_OK);
    @assert(strcmp(value.c_str(), "1234") == 0);
    @assert(value.strncat("abcdef", 3) == STRING_OK);
    @assert(strcmp(value.c_str(), "1234abc") == 0);
    value.destroy();
}

@test "string comparison facade" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("alphabet") == STRING_OK);
    @assert(value.strcmp("alphabet") == 0);
    @assert(value.strcmp("alphaz") < 0);
    @assert(value.strncmp("alphaZZ", 5) == 0);
    @assert(value.strcoll("alphabet") == 0);
    value.destroy();
}

@test "string search facade" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("abc:def:ghi") == STRING_OK);
    const char* first = value.strchr(':');
    const char* last = value.strrchr(':');
    const char* sub = value.strstr("def");
    const char* any = value.strpbrk("xyz:g");
    @assert(first != NULL && strcmp(first, ":def:ghi") == 0);
    @assert(last != NULL && strcmp(last, ":ghi") == 0);
    @assert(sub != NULL && strcmp(sub, "def:ghi") == 0);
    @assert(any != NULL && *any == ':');
    value.destroy();
}

@test "string span facade" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("aaab12") == STRING_OK);
    @assert(value.strspn("ab") == 4);
    @assert(value.strcspn("12") == 4);
    value.destroy();
}

@test "string strxfrm facade" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.strxfrm("locale-text") == STRING_OK);
    @assert(value.size() == strlen(value.c_str()));
    @assert(value.strcoll("locale-text") == 0);
    value.destroy();
}

@test "string raw memory facade" {
    char source[8] = "abcdef";
    char destination[8];
    @assert(string.memset(destination, 0, sizeof(destination)) == destination);
    @assert(string.memcpy(destination, source, 7) == destination);
    @assert(strcmp(destination, "abcdef") == 0);
    @assert(string.memcmp(destination, source, 7) == 0);
    @assert(string.memchr(destination, 'd', 7) == &destination[3]);
    @assert(string.memmove(destination + 1, destination, 5) == destination + 1);
    @assert(destination[1] == 'a');
    @assert(destination[5] == 'e');
}

@test "string strerror facade" {
    char* message = string.strerror(EINVAL);
    @assert(message != NULL);
    @assert(strlen(message) > 0);
}

@test "string strtok facade uses raw caller buffer" {
    char buffer[32] = "one,two,three";
    char* first = string.strtok(buffer, ",");
    char* second = string.strtok(NULL, ",");
    char* third = string.strtok(NULL, ",");
    @assert(first != NULL && strcmp(first, "one") == 0);
    @assert(second != NULL && strcmp(second, "two") == 0);
    @assert(third != NULL && strcmp(third, "three") == 0);
    @assert(string.strtok(NULL, ",") == NULL);
}

@test "string indent all logical lines" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("alpha\nbeta\n") == STRING_OK);
    @assert(value.indent(3) == STRING_OK);
    @assert(strcmp(value.c_str(), "   alpha\n   beta\n") == 0);
    @assert(value.size() == strlen("   alpha\n   beta\n"));
    value.destroy();
}

@test "string indent handles blank lines" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("a\n\nb") == STRING_OK);
    @assert(value.indent(2) == STRING_OK);
    @assert(strcmp(value.c_str(), "  a\n  \n  b") == 0);
    value.destroy();
}

@test "string dedent removes up to requested spaces" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("    alpha\n  beta\n      gamma") == STRING_OK);
    @assert(value.dedent(3) == STRING_OK);
    @assert(strcmp(value.c_str(), " alpha\nbeta\n   gamma") == 0);
    value.destroy();
}

@test "string trim indent removes common nonblank indent" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("    alpha\n      beta\n\n    gamma") == STRING_OK);
    @assert(string.trim_indent(&value) == STRING_OK);
    @assert(strcmp(value.c_str(), "alpha\n  beta\n\ngamma") == 0);
    value.destroy();
}

@test "string trim indent ignores blank line spaces" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("    alpha\n \n      beta") == STRING_OK);
    @assert(string.trim_indent(&value) == STRING_OK);
    @assert(strcmp(value.c_str(), "alpha\n\n  beta") == 0);
    value.destroy();
}

@test "string zero indentation operations are noops" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("x\ny") == STRING_OK);
    @assert(value.indent(0) == STRING_OK);
    @assert(value.dedent(0) == STRING_OK);
    @assert(strcmp(value.c_str(), "x\ny") == 0);
    value.destroy();
}

@test "string negative indentation is rejected without mutation" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("text") == STRING_OK);
    @assert(value.indent(-1) == STRING_ERROR_INVALID_ARGUMENT);
    @assert(strcmp(value.c_str(), "text") == 0);
    @assert(value.dedent(-1) == STRING_ERROR_INVALID_ARGUMENT);
    @assert(strcmp(value.c_str(), "text") == 0);
    value.destroy();
}

@test "string null argument validation" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign(NULL) == STRING_ERROR_INVALID_ARGUMENT);
    @assert(value.append(NULL) == STRING_ERROR_INVALID_ARGUMENT);
    @assert(value.strcpy(NULL) == STRING_ERROR_INVALID_ARGUMENT);
    @assert(value.strncpy(NULL, 1) == STRING_ERROR_INVALID_ARGUMENT);
    @assert(value.strcat(NULL) == STRING_ERROR_INVALID_ARGUMENT);
    @assert(value.strncat(NULL, 1) == STRING_ERROR_INVALID_ARGUMENT);
    value.destroy();
}

@test "string capacity reuse across smaller assignments" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("this is a longer initial string") == STRING_OK);
    size_t capacity = value.capacity_of();
    @assert(value.assign("tiny") == STRING_OK);
    @assert(value.capacity_of() == capacity);
    @assert(strcmp(value.c_str(), "tiny") == 0);
    value.destroy();
}

@test "string empty source operations allocate a valid terminator" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("") == STRING_OK);
    @assert(value.data != NULL);
    @assert(value.size() == 0);
    @assert(strcmp(value.c_str(), "") == 0);
    value.destroy();

    @assert(value.init() == STRING_OK);
    @assert(value.strcpy("") == STRING_OK);
    @assert(value.data != NULL);
    @assert(strcmp(value.c_str(), "") == 0);
    @assert(value.append("") == STRING_OK);
    @assert(strcmp(value.c_str(), "") == 0);
    value.destroy();
}

@test "string assignment supports internal substrings" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("abcdef") == STRING_OK);
    const char* suffix = value.c_str() + 2;
    @assert(value.assign(suffix) == STRING_OK);
    @assert(strcmp(value.c_str(), "cdef") == 0);
    value.destroy();
}

@test "string append supports self alias and growth" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("abc") == STRING_OK);
    @assert(value.append(value.c_str()) == STRING_OK);
    @assert(strcmp(value.c_str(), "abcabc") == 0);
    @assert(value.append_n(value.c_str() + 1, 2) == STRING_OK);
    @assert(strcmp(value.c_str(), "abcabcbc") == 0);
    value.destroy();
}

@test "string strcat facade supports self alias" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.strcpy("xy") == STRING_OK);
    @assert(value.strcat(value.c_str()) == STRING_OK);
    @assert(strcmp(value.c_str(), "xyxy") == 0);
    @assert(value.strncat(value.c_str() + 1, 2) == STRING_OK);
    @assert(strcmp(value.c_str(), "xyxyyx") == 0);
    value.destroy();
}

@test "string strxfrm supports own buffer as source" {
    string value;
    @assert(value.init() == STRING_OK);
    @assert(value.assign("transform-me") == STRING_OK);
    @assert(value.strxfrm(value.c_str()) == STRING_OK);
    @assert(value.size() == strlen(value.c_str()));
    value.destroy();
}

@test "string static trim indent validates null" {
    @assert(string.trim_indent(NULL) == STRING_ERROR_INVALID_ARGUMENT);
}
