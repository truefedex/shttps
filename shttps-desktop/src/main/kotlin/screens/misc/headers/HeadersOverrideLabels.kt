package com.phlox.simpleserver.screens.misc.headers

import androidx.compose.runtime.Composable
import com.phlox.server.handlers.router.middleware.impl.CustomHeadersMiddleware
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.if_exists_append
import com.phlox.simpleserver.shttps_desktop.generated.resources.if_exists_ignore
import com.phlox.simpleserver.shttps_desktop.generated.resources.if_exists_override
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ifHeadersExistLabel(value: CustomHeadersMiddleware.IfHeadersExist): String {
    return when (value) {
        CustomHeadersMiddleware.IfHeadersExist.OVERRIDE -> stringResource(Res.string.if_exists_override)
        CustomHeadersMiddleware.IfHeadersExist.APPEND -> stringResource(Res.string.if_exists_append)
        CustomHeadersMiddleware.IfHeadersExist.IGNORE -> stringResource(Res.string.if_exists_ignore)
    }
}
