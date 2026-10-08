/*
 * 最小化的 BSD 风格尾队列（STAILQ）实现，供 ebur128.c 在 MSVC 上编译使用。
 *
 * ebur128 的可移植方案是「换成任意 BSD 风格的队列实现」：
 *     /* This can be replaced by any BSD-like queue implementation. *\/
 *     #include <sys/queue.h>
 *
 * MSVC 没有 <sys/queue.h>，而 ebur128.c 只用到 STAILQ_* 这一组宏，因此这里
 * 按 FreeBSD <sys/queue.h> 的语义手写这 8 个宏，而不是引入整份 BSD 头文件。
 *
 * 语义与 FreeBSD 版本一致：
 *   - STAILQ 是单向链表，头结点保存 stqh_first 和指向「最后一个 next 指针」
 *     的 stqh_last，因此尾部插入是 O(1)。
 *   - STAILQ_REMOVE_HEAD 不需要遍历，因此在出队场景下是 O(1)，这也是
 *     ebur128 选它的原因。
 *
 * 仅在 MSVC / 缺少系统 <sys/queue.h> 时通过 include 路径注入。
 */

#ifndef LYRICO_COMPAT_SYS_QUEUE_H
#define LYRICO_COMPAT_SYS_QUEUE_H

#include <stddef.h> /* NULL */

#define STAILQ_HEAD(name, type)                                                \
    struct name {                                                              \
        struct type *stqh_first;                                               \
        struct type **stqh_last;                                               \
    }

#define STAILQ_HEAD_INITIALIZER(head)                                          \
    { NULL, &(head).stqh_first }

#define STAILQ_ENTRY(type)                                                     \
    struct {                                                                   \
        struct type *stqe_next;                                                \
    }

#define STAILQ_INIT(head)                                                      \
    do {                                                                       \
        (head)->stqh_first = NULL;                                             \
        (head)->stqh_last = &(head)->stqh_first;                               \
    } while (0)

#define STAILQ_EMPTY(head) ((head)->stqh_first == NULL)

#define STAILQ_FIRST(head) ((head)->stqh_first)

#define STAILQ_NEXT(elm, field) ((elm)->field.stqe_next)

#define STAILQ_LAST(head, type, field)                                         \
    (STAILQ_EMPTY(head)                                                        \
             ? NULL                                                            \
             : ((struct type *)(void *)((char *)((head)->stqh_last) -          \
                                        offsetof(struct type, field))))

#define STAILQ_INSERT_HEAD(head, elm, field)                                   \
    do {                                                                       \
        if (((elm)->field.stqe_next = (head)->stqh_first) == NULL) {           \
            (head)->stqh_last = &(elm)->field.stqe_next;                       \
        }                                                                      \
        (head)->stqh_first = (elm);                                            \
    } while (0)

#define STAILQ_INSERT_TAIL(head, elm, field)                                   \
    do {                                                                       \
        (elm)->field.stqe_next = NULL;                                         \
        *(head)->stqh_last = (elm);                                            \
        (head)->stqh_last = &(elm)->field.stqe_next;                           \
    } while (0)

#define STAILQ_INSERT_AFTER(head, listelm, elm, field)                         \
    do {                                                                       \
        if (((elm)->field.stqe_next = (listelm)->field.stqe_next) == NULL) {   \
            (head)->stqh_last = &(elm)->field.stqe_next;                       \
        }                                                                      \
        (listelm)->field.stqe_next = (elm);                                    \
    } while (0)

#define STAILQ_REMOVE_HEAD(head, field)                                        \
    do {                                                                       \
        if (((head)->stqh_first = (head)->stqh_first->field.stqe_next) ==      \
            NULL) {                                                            \
            (head)->stqh_last = &(head)->stqh_first;                           \
        }                                                                      \
    } while (0)

#define STAILQ_REMOVE_AFTER(head, elm, field)                                  \
    do {                                                                       \
        if (((elm)->field.stqe_next = (elm)->field.stqe_next->field.stqe_next) \
                    == NULL) {                                                 \
            (head)->stqh_last = &(elm)->field.stqe_next;                       \
        }                                                                      \
    } while (0)

#define STAILQ_FOREACH(var, head, field)                                       \
    for ((var) = STAILQ_FIRST(head); (var); (var) = STAILQ_NEXT(var, field))

#define STAILQ_FOREACH_SAFE(var, head, field, tvar)                            \
    for ((var) = STAILQ_FIRST(head);                                           \
         (var) && ((tvar) = STAILQ_NEXT(var, field), 1); (var) = (tvar))

#endif /* LYRICO_COMPAT_SYS_QUEUE_H */
