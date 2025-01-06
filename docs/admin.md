# Administration Site

In order to do some administration that can not be accessible to "regular" users,
there is a separate admin site.  The code for this site is also situated in the
[gui](../gui) directory, although it is put into a separate module.  The `index.html`
page is generated at build time for each of the modules, and included in the
container image.  This means that regular users don't even have access to the code
that is provided for the admin site.  Of course, there is an additional security
layer at the backend that checks if certain admin calls can even be executed by
the user, by inspecting the token permissions.